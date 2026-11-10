package com.deepseekharness.app.plugins;

import android.os.FileObserver;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 只报告失效，不读取插件内容，不推导安装、激活或加载成功。
 *
 * <p>所有目录枚举及 FileObserver 构建/释放均在单独后台队列。前台期间按事件修复观察树， 后台释放观察器；无定时全盘扫描、常驻服务或唤醒锁。回调须由 Engine 转交其调度队列。
 */
public final class PluginChangeDetector implements AutoCloseable {
  public enum Kind {
    CONTENT,
    RUNTIME_PROJECTION,
    SOURCE_IDENTITY
  }

  /** Adapter 提供实际宿主路径；trustedRoot 必须是已经核验的宿主边界，不能传 guest 别名。 */
  public static final class WatchPath {
    public final File trustedRoot;
    public final File path;
    public final Kind kind;
    public final boolean recursive;

    public WatchPath(File trustedRoot, File path, Kind kind, boolean recursive) {
      this.trustedRoot =
          Objects.requireNonNull(trustedRoot).getAbsoluteFile().toPath().normalize().toFile();
      this.path = Objects.requireNonNull(path).getAbsoluteFile().toPath().normalize().toFile();
      this.kind = Objects.requireNonNull(kind);
      this.recursive = recursive;
      if (!this.path.toPath().startsWith(this.trustedRoot.toPath()))
        throw new IllegalArgumentException("观察路径越过已核宿主边界");
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof WatchPath)) return false;
      WatchPath that = (WatchPath) other;
      return trustedRoot.equals(that.trustedRoot)
          && path.equals(that.path)
          && kind == that.kind
          && recursive == that.recursive;
    }

    @Override
    public int hashCode() {
      return Objects.hash(trustedRoot, path, kind, recursive);
    }
  }

  // Linux 的失效/目录标记没有全部暴露为 FileObserver 常量。
  static final int UNMOUNT = 0x2000, OVERFLOW = 0x4000, IGNORED = 0x8000, IS_DIRECTORY = 0x40000000;
  private static final int IN_ONLYDIR = 0x01000000, IN_DONT_FOLLOW = 0x02000000;
  static final int CHANGE_MASK =
      FileObserver.MODIFY
          | FileObserver.ATTRIB
          | FileObserver.CLOSE_WRITE
          | FileObserver.MOVED_FROM
          | FileObserver.MOVED_TO
          | FileObserver.CREATE
          | FileObserver.DELETE
          | FileObserver.DELETE_SELF
          | FileObserver.MOVE_SELF;
  private static final int INVALID_MASK = UNMOUNT | OVERFLOW | IGNORED;
  private static final int SELF_MASK =
      FileObserver.DELETE_SELF | FileObserver.MOVE_SELF | INVALID_MASK;
  private static final int STRUCTURAL_MASK =
      FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_FROM | FileObserver.MOVED_TO;
  private static final int MAX_PATHS = 128;

  interface Clock {
    long now();
  }

  interface Cancellation {
    void cancel();
  }

  interface Scheduler {
    void execute(Runnable work);

    Cancellation schedule(Runnable work, long delayMs);

    void close();
  }

  interface EventSink {
    void event(int event, String child);
  }

  interface Watch {
    void stop();
  }

  interface ObserverFactory {
    Watch start(File directory, EventSink events) throws IOException;
  }

  static final class Limits {
    final int directories, entries, depth;
    final long buildMillis;

    Limits(int directories, int entries, int depth, long buildMillis) {
      if (directories < 1 || entries < 1 || depth < 1 || buildMillis < 1)
        throw new IllegalArgumentException("观察预算必须为正数");
      this.directories = directories;
      this.entries = entries;
      this.depth = depth;
      this.buildMillis = buildMillis;
    }
  }

  private final Object lock = new Object();
  private final Runnable onDirty, onRuntimeDirty, onReconcile;
  private final Scheduler worker;
  private final Clock clock;
  private final ObserverFactory factory;
  private final Limits limits;
  private final PluginChangeCoalescer repairs = new PluginChangeCoalescer(100, 1000, 750);
  private final Map<Path, Registration> registrations = new HashMap<>(); // 仅后台队列访问。
  private List<WatchPath> desired = Collections.emptyList();
  private Object identity;
  private long generation;
  private boolean foreground, closed, drainQueued, pendingContent, pendingRuntime;
  private boolean pendingRepair, pendingForce, pendingReconcile, deferredContent;
  private Cancellation repairTask;
  private volatile boolean coverageLimited;

  /** 单回调版本不会把动态 heartbeat 升级为完整扫描。 */
  public PluginChangeDetector(Runnable onDirty) {
    this(onDirty, () -> {});
  }

  public PluginChangeDetector(Runnable onDirty, Runnable onRuntimeDirty) {
    this(onDirty, onRuntimeDirty, onDirty);
  }

  /** onReconcile 只请求核对；Engine 根据来源/缓存年龄决定是否扫描，不能把无事件视为内容证明。 */
  public PluginChangeDetector(Runnable onDirty, Runnable onRuntimeDirty, Runnable onReconcile) {
    this(
        onDirty,
        onRuntimeDirty,
        onReconcile,
        new Background(),
        () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() & Long.MAX_VALUE),
        PluginChangeDetector::nativeWatch,
        new Limits(1024, 32768, 48, 1000));
  }

  PluginChangeDetector(
      Runnable onDirty,
      Runnable onRuntimeDirty,
      Scheduler worker,
      Clock clock,
      ObserverFactory factory,
      Limits limits) {
    this(onDirty, onRuntimeDirty, onDirty, worker, clock, factory, limits);
  }

  PluginChangeDetector(
      Runnable onDirty,
      Runnable onRuntimeDirty,
      Runnable onReconcile,
      Scheduler worker,
      Clock clock,
      ObserverFactory factory,
      Limits limits) {
    this.onDirty = Objects.requireNonNull(onDirty);
    this.onRuntimeDirty = Objects.requireNonNull(onRuntimeDirty);
    this.onReconcile = Objects.requireNonNull(onReconcile);
    this.worker = Objects.requireNonNull(worker);
    this.clock = Objects.requireNonNull(clock);
    this.factory = Objects.requireNonNull(factory);
    this.limits = Objects.requireNonNull(limits);
  }

  /** 仅复制描述符和比较来源身份；主线程不会探测磁盘。初始为非前台。 */
  public void bind(List<WatchPath> paths, Object sourceIdentity) {
    Objects.requireNonNull(paths);
    if (paths.size() > MAX_PATHS) throw new IllegalArgumentException("观察入口超过预算");
    List<WatchPath> copy = new ArrayList<>(paths);
    for (WatchPath path : copy) Objects.requireNonNull(path);
    synchronized (lock) {
      if (closed || (Objects.equals(identity, sourceIdentity) && desired.equals(copy))) return;
      identity = sourceIdentity;
      desired = Collections.unmodifiableList(copy);
      generation++;
      pendingForce = pendingRepair = pendingReconcile = true;
      enqueueDrainLocked();
    }
  }

  /** 恢复前台必补偿请求；离开前台使旧回调立刻失效，后台队列负责关闭本机观察。 */
  public void foreground(boolean value) {
    synchronized (lock) {
      if (closed || foreground == value) return;
      foreground = value;
      generation++;
      deferredContent |= pendingContent;
      pendingContent = false;
      pendingForce = pendingRepair = pendingReconcile = value;
      pendingRuntime = false;
      enqueueDrainLocked();
    }
  }

  /** 超出枚举/目录/深度预算或路径暂不可读时为 true；不能把有限观察覆盖当作完整证明。 */
  public boolean coverageLimited() {
    return coverageLimited;
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      foreground = false;
      generation++;
      worker.execute(
          () -> {
            cancelRepair();
            stopAll();
            worker.close();
          });
    }
  }

  private void enqueueDrainLocked() {
    if (!drainQueued) {
      drainQueued = true;
      worker.execute(this::drain);
    }
  }

  private void drain() {
    boolean content, runtime, repair, force, reconcile, active;
    long epoch;
    synchronized (lock) {
      drainQueued = false;
      active = !closed && foreground;
      epoch = generation;
      content = pendingContent || deferredContent;
      runtime = pendingRuntime;
      repair = pendingRepair;
      force = pendingForce;
      reconcile = pendingReconcile;
      pendingContent = pendingRuntime = pendingRepair = pendingForce = pendingReconcile = false;
      if (active) deferredContent = false;
      else if (!closed) deferredContent |= content;
    }
    if (!active) {
      cancelRepair();
      stopAll();
      return;
    }
    if (content) dirtyCallback(epoch);
    if (runtime) callback(onRuntimeDirty, epoch);
    if (reconcile) callback(onReconcile, epoch);
    if (repair) {
      if (force) {
        // 来源换代及本机失效不能复用旧观察器，即使路径与 inode 恰好未变。
        stopAll();
        repairs.requestNow(clock.now());
      } else repairs.markDirty(clock.now());
      scheduleRepair();
    }
  }

  private void scheduleRepair() {
    cancelRepair();
    long delay = repairs.nextDelayMillis(clock.now());
    if (delay >= 0) repairTask = worker.schedule(this::rebuild, delay);
  }

  private void cancelRepair() {
    if (repairTask != null) {
      repairTask.cancel();
      repairTask = null;
    }
  }

  private void rebuild() {
    repairTask = null;
    List<WatchPath> paths;
    long epoch;
    synchronized (lock) {
      if (closed || !foreground) return;
      paths = desired;
      epoch = generation;
    }
    if (!repairs.beginScan(clock.now())) {
      scheduleRepair();
      return;
    }
    Plan plan = new Plan(epoch);
    try {
      // 先绑定每个入口的父链/顶层，防止一个大插件吃掉 profile/journal 的观察配额。
      for (WatchPath path : paths) plan.seed(path);
      plan.descend();
      if (!active(epoch)) return;
      Map<Path, Registration> next = new HashMap<>();
      for (Spec spec : plan.specs.values()) {
        if (!active(epoch)) break;
        Registration existing = registrations.get(spec.path);
        Registration created = new Registration(spec, epoch);
        try {
          if (!safeDirectory(spec.root, spec.path, spec.device).equals(spec.stamp)) {
            plan.limited = plan.changed = true;
            continue;
          }
          if (existing != null && existing.epoch == epoch && existing.stamp.equals(spec.stamp)) {
            existing.rules = List.copyOf(spec.rules);
            next.put(spec.path, existing);
            continue;
          }
          created.watch =
              factory.start(spec.path.toFile(), (event, child) -> event(created, event, child));
          if (!safeDirectory(spec.root, spec.path, spec.device).equals(spec.stamp)) {
            created.stop();
            plan.limited = plan.changed = true;
            continue;
          }
          next.put(spec.path, created);
        } catch (IOException | RuntimeException unavailable) {
          created.stop();
          plan.limited = true;
          plan.changed |= pathChanged(unavailable);
        }
      }
      for (Registration old : registrations.values()) {
        if (next.get(old.path) != old) old.stop();
      }
      registrations.clear();
      if (active(epoch)) registrations.putAll(next);
      else for (Registration stale : next.values()) stale.stop();
      coverageLimited = plan.limited;
      // 新观察器建立后请求核对，实测到身份变化则必须保留真实 dirty。
      if (plan.changed) dirtyCallback(epoch);
      else callback(onReconcile, epoch);
    } finally {
      if (plan.changed && active(epoch)) repairs.markDirty(clock.now());
      repairs.scanFinished(clock.now());
      if (repairs.hasPending()) scheduleRepair();
    }
  }

  private static boolean pathChanged(Exception error) {
    return error instanceof java.nio.file.NoSuchFileException
        || "PLUGIN_WATCH_ROOT_CHANGED".equals(error.getMessage())
        || "PLUGIN_WATCH_PATH_CHANGED".equals(error.getMessage());
  }

  private void event(Registration registration, int event, String child) {
    if (registration.retired || (event & (CHANGE_MASK | INVALID_MASK)) == 0) return;
    boolean invalid = (event & SELF_MASK) != 0;
    boolean content = false, runtime = false, repair = invalid;
    for (Rule rule : registration.rules) {
      if (!invalid && !rule.matches(child)) continue;
      if (rule.kind == Kind.RUNTIME_PROJECTION) runtime = true;
      else content = true;
      if ((event & STRUCTURAL_MASK) != 0 && (rule.ancestor || (event & IS_DIRECTORY) != 0))
        repair = true;
    }
    if (!content && !runtime && !repair) return;
    synchronized (lock) {
      if (closed || !foreground || registration.retired || registration.epoch != generation) return;
      pendingContent |= content;
      pendingRuntime |= runtime;
      pendingRepair |= repair;
      pendingForce |= invalid;
      enqueueDrainLocked();
    }
  }

  private boolean active(long epoch) {
    synchronized (lock) {
      return !closed && foreground && generation == epoch;
    }
  }

  private void dirtyCallback(long epoch) {
    if (callback(onDirty, epoch)) return;
    synchronized (lock) {
      if (!closed) deferredContent = true;
    }
  }

  private boolean callback(Runnable callback, long epoch) {
    if (!active(epoch)) return false;
    // Engine 回调只投递失效消息；一处异常不应永久终止本机观察器线程。
    try {
      callback.run();
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private void stopAll() {
    for (Registration registration : registrations.values()) registration.stop();
    registrations.clear();
  }

  private final class Plan {
    final long epoch, startedAt = clock.now();
    final Map<Path, Spec> specs = new LinkedHashMap<>();
    final ArrayDeque<Branch> branches = new ArrayDeque<>();
    final Set<String> visited = new HashSet<>();
    int inspected;
    boolean limited, changed;

    Plan(long epoch) {
      this.epoch = epoch;
    }

    boolean budget() {
      if (!active(epoch) || clock.now() - startedAt >= limits.buildMillis) {
        limited = true;
        return false;
      }
      return true;
    }

    void seed(WatchPath input) {
      if (!budget()) return;
      Path root = input.trustedRoot.toPath(), target = input.path.toPath();
      try {
        Stamp base = stamp(root);
        if (!base.directory || base.symlink) {
          limited = true;
          return;
        }
        Path parent = root;
        int ancestors = 0;
        for (Path component : root.relativize(target)) {
          if (component.toString().isEmpty()) continue;
          if (!budget() || ++ancestors > limits.depth) {
            limited = true;
            return;
          }
          Path child = parent.resolve(component);
          boolean ancestor = !child.equals(target);
          add(root, parent, base.device, new Rule(input.kind, component.toString(), ancestor));
          if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) return;
          Stamp state = stamp(child);
          if (state.symlink || state.device != base.device) {
            limited = true;
            return;
          }
          if (!state.directory) {
            if (ancestor) limited = true;
            return;
          }
          parent = child;
        }
        // 身份根只观察自身/父项生命周期，不能让根目录里的每个 heartbeat 触发 full scan。
        Rule rule = new Rule(input.kind, input.kind == Kind.SOURCE_IDENTITY ? "" : null, false);
        add(root, target, base.device, rule);
        if (input.recursive) branches.add(new Branch(root, target, base.device, input.kind, 0));
      } catch (IOException | RuntimeException unavailable) {
        limited = true;
      }
    }

    void add(Path root, Path directory, long device, Rule rule) throws IOException {
      Spec spec = specs.get(directory);
      if (spec == null) {
        if (specs.size() >= limits.directories) {
          limited = true;
          return;
        }
        spec = new Spec(root, directory, device, safeDirectory(root, directory, device));
        specs.put(directory, spec);
      }
      if (!spec.rules.contains(rule)) spec.rules.add(rule);
    }

    void descend() {
      while (!branches.isEmpty() && budget()) {
        Branch branch = branches.removeFirst();
        if (!visited.add(branch.path + "\n" + branch.kind)) continue;
        if (branch.depth >= limits.depth) {
          limited = true;
          continue;
        }
        if (!specs.containsKey(branch.path)) {
          limited = true;
          continue;
        }
        try {
          Stamp before = safeDirectory(branch.root, branch.path, branch.device);
          try (DirectoryStream<Path> children = Files.newDirectoryStream(branch.path)) {
            for (Path child : children) {
              if (++inspected > limits.entries || !budget()) {
                limited = true;
                return;
              }
              Stamp state = stamp(child);
              if (state.symlink || state.device != branch.device) continue;
              if (!state.directory) continue;
              add(branch.root, child, branch.device, new Rule(branch.kind, null, false));
              if (specs.containsKey(child))
                branches.addLast(
                    new Branch(branch.root, child, branch.device, branch.kind, branch.depth + 1));
            }
          }
          if (!safeDirectory(branch.root, branch.path, branch.device).equals(before))
            limited = changed = true;
        } catch (IOException | RuntimeException unavailable) {
          limited = true;
          changed |= pathChanged(unavailable);
        }
      }
    }
  }

  private static Stamp safeDirectory(Path root, Path path, long device) throws IOException {
    if (!path.startsWith(root)) throw new IOException("PLUGIN_WATCH_OUTSIDE_ROOT");
    Path current = root;
    Stamp state = stamp(current);
    if (!state.directory || state.symlink || state.device != device)
      throw new IOException("PLUGIN_WATCH_ROOT_CHANGED");
    for (Path component : root.relativize(path)) {
      if (component.toString().isEmpty()) continue;
      current = current.resolve(component);
      state = stamp(current);
      if (!state.directory || state.symlink || state.device != device)
        throw new IOException("PLUGIN_WATCH_PATH_CHANGED");
    }
    return state;
  }

  private static Stamp stamp(Path path) throws IOException {
    BasicFileAttributes value =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Object device = Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS);
    if (!(device instanceof Number) || value.fileKey() == null)
      throw new IOException("PLUGIN_WATCH_IDENTITY_UNAVAILABLE");
    return new Stamp(
        value.isDirectory(),
        value.isSymbolicLink(),
        ((Number) device).longValue(),
        value.fileKey());
  }

  private static final class Stamp {
    final boolean directory, symlink;
    final long device;
    final Object key;

    Stamp(boolean directory, boolean symlink, long device, Object key) {
      this.directory = directory;
      this.symlink = symlink;
      this.device = device;
      this.key = key;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Stamp)) return false;
      Stamp that = (Stamp) other;
      return directory == that.directory
          && symlink == that.symlink
          && device == that.device
          && key.equals(that.key);
    }

    @Override
    public int hashCode() {
      return Objects.hash(directory, symlink, device, key);
    }
  }

  private static final class Rule {
    final Kind kind;
    final String child;
    final boolean ancestor;

    Rule(Kind kind, String child, boolean ancestor) {
      this.kind = kind;
      this.child = child;
      this.ancestor = ancestor;
    }

    boolean matches(String value) {
      return child == null || child.equals(value);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Rule)) return false;
      Rule that = (Rule) other;
      return kind == that.kind && Objects.equals(child, that.child) && ancestor == that.ancestor;
    }

    @Override
    public int hashCode() {
      return Objects.hash(kind, child, ancestor);
    }
  }

  private static final class Spec {
    final Path root, path;
    final long device;
    final Stamp stamp;
    final List<Rule> rules = new ArrayList<>();

    Spec(Path root, Path path, long device, Stamp stamp) {
      this.root = root;
      this.path = path;
      this.device = device;
      this.stamp = stamp;
    }
  }

  private static final class Branch {
    final Path root, path;
    final long device;
    final Kind kind;
    final int depth;

    Branch(Path root, Path path, long device, Kind kind, int depth) {
      this.root = root;
      this.path = path;
      this.device = device;
      this.kind = kind;
      this.depth = depth;
    }
  }

  private static final class Registration {
    final Path path;
    final Stamp stamp;
    final long epoch;
    volatile List<Rule> rules;
    volatile boolean retired;
    Watch watch;

    Registration(Spec spec, long epoch) {
      path = spec.path;
      stamp = spec.stamp;
      this.epoch = epoch;
      rules = List.copyOf(spec.rules);
    }

    void stop() {
      retired = true;
      if (watch != null) watch.stop();
    }
  }

  @SuppressWarnings("deprecation")
  @android.annotation.SuppressLint("WrongConstant")
  private static Watch nativeWatch(File directory, EventSink events) {
    // AOSP FileObserver 的 Java/JNI 原样转交 mask 给 inotify_add_watch；公开 IntDef 只列事件位。
    // IN_ONLYDIR | IN_DONT_FOLLOW 是注册保护位：路径在注册瞬间被替换为 symlink 时也不追随目标。
    // 此处局部声明该差异，不能删掉保护位或放宽全工程 Lint。
    FileObserver observer =
        new FileObserver(directory.getAbsolutePath(), CHANGE_MASK | IN_ONLYDIR | IN_DONT_FOLLOW) {
          @Override
          public void onEvent(int event, String path) {
            events.event(event, path);
          }
        };
    observer.startWatching();
    return observer::stopWatching;
  }

  private static final class Background implements Scheduler {
    final ScheduledThreadPoolExecutor executor;

    Background() {
      executor =
          new ScheduledThreadPoolExecutor(
              1,
              work -> {
                Thread thread = new Thread(work, "plugin-file-observers");
                thread.setDaemon(true);
                return thread;
              });
      executor.setRemoveOnCancelPolicy(true);
      executor.setKeepAliveTime(30, TimeUnit.SECONDS);
      executor.allowCoreThreadTimeOut(true);
      executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public void execute(Runnable work) {
      executor.execute(work);
    }

    @Override
    public Cancellation schedule(Runnable work, long delayMs) {
      var future = executor.schedule(work, delayMs, TimeUnit.MILLISECONDS);
      return () -> future.cancel(false);
    }

    @Override
    public void close() {
      executor.shutdown();
    }
  }
}
