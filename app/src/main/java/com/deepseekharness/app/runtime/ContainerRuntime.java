package com.deepseekharness.app.runtime;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 容器运行时抽象：把「怎么进 rootfs 执行命令」独立出来，让 proot 与 proroot 并存、可切换。
 *
 * <p>两条实现：
 * <ul>
 *   <li>{@link Proot} —— Termux 的 proot（APK 内置 libproot.so），默认稳定路径；</li>
 *   <li>{@link Proroot} —— coderredlab/proroot，LD_PRELOAD + 二进制补丁，零 ptrace 开销。</li>
 * </ul>
 *
 * <p>proroot 的 argv 里<b>没有</b> {@code --kill-on-exit}：{@code Process.destroy()} 只杀启动器，
 * 容器里的 node 会变孤儿继续跑 —— 所以「停止」必须按 pid 精确杀（见 {@code WebProcSel}），
 * 不能指望杀启动器传播信号。
 */
public interface ContainerRuntime {

  String id();

  String displayName();

  boolean available();

  String unavailableReason();

  /** 组装进入 rootfs 的命令前缀（不含最终要跑的 /bin/bash …）。 */
  List<String> baseArgv(File rootfsDir, boolean hardlinkSupported);

  /** Null dataDomain is an explicit independent rescue domain, never inferred from rootfs parents. */
  List<String> baseArgv(File rootfsDir, boolean hardlinkSupported, File dataDomain);

  /** 设置进程环境（LD_LIBRARY_PATH、TMPDIR 之类）。 */
  void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir)
      throws java.io.IOException;

  /** 首次使用前的准备。抛异常表示失败，调用方应回退。 */
  void prepare() throws Exception;

  // ==================================================================

  /** Termux proot，APK 内置。 */
  class Proot implements ContainerRuntime {
    private final File nativeLibProot;
    private final Context context;
    private final RuntimeHostPorts.Settings explicitSettings;

    public Proot(Context ctx, File nativeLibProot) {
      this(ctx, nativeLibProot, null);
    }

    public Proot(Context ctx, File nativeLibProot, RuntimeHostPorts.Settings settings) {
      this.nativeLibProot = nativeLibProot;
      this.context = ctx;
      this.explicitSettings = settings;
    }

    @Override
    public String id() {
      return "proot";
    }

    @Override
    public String displayName() {
      return com.deepseekharness.app.util.UiText.text("proot（内置，稳定）");
    }

    @Override
    public boolean available() {
      return nativeLibProot != null && nativeLibProot.isFile() && nativeLibProot.length() > 0;
    }

    @Override
    public String unavailableReason() {
      return available()
          ? ""
          : com.deepseekharness.app.util.UiText.text("APK 内的 libproot.so 缺失（安装包可能损坏，建议重装）");
    }

    @Override
    public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported) {
      if (context == null) throw new IllegalStateException("DATA_DOMAIN_CONTEXT_MISSING");
      return baseArgv(rootfsDir, hardlinkSupported, context.getFilesDir());
    }

    @Override
    public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported, File dataDomain) {
      List<String> argv = new ArrayList<>();
      argv.add(nativeLibProot.getAbsolutePath());
      // 只有文件系统不支持硬链接时才需要 link2symlink 模拟（会破坏 dsh write 工具）。
      // Android app 私有目录（/data/…，ext4/f2fs）本来就支持硬链接，扩展纯属多余。
      if (!hardlinkSupported) {
        argv.add("--link2symlink");
        // L2S 链保存宿主绝对路径，容器必须能按同一路径访问，否则 dpkg 的 chown/stat 报 ENOENT。
        File l2s = new File(rootfsDir, ".l2s");
        l2s.mkdirs();
        argv.add("-b");
        argv.add(l2s.getAbsolutePath() + ":" + l2s.getAbsolutePath());
      }
      argv.add("-L");
      argv.add("--kill-on-exit");
      argv.add("-0");
      argv.add("--rootfs=" + rootfsDir.getAbsolutePath());
      argv.add("--cwd=/root");
      // 运行根自镜像：guest 内按 /proc/self/maps 回读自身文件依赖它，见 rootfsSelfMirror()。
      argv.add("-b");
      argv.add(rootfsSelfMirror(rootfsDir));
      for (Bind b : binds(com.deepseekharness.app.StorageAccess.granted(context))) {
        if (!new File(b.host()).exists()) {
          if (context != null)
            RuntimeHostPorts.fromOwner(context.getApplicationContext())
                .record("BIND_SKIPPED", b.host() + ": missing or inaccessible");
          continue;
        }
        argv.add("-b");
        argv.add(b.host().equals(b.guest()) ? b.host() : b.host() + ":" + b.guest());
      }
      if (dataDomain != null) UserDataBindings.append(argv, rootfsDir, dataDomain);
      return argv;
    }

    /**
     * 运行根自我镜像绑定：guest 内按 {@code /proc/self/maps} 记录的宿主绝对路径回读自身文件。
     *
     * <p>proot 用宿主路径 exec/mmap，maps 里是内核解析后的 canonical 路径；而运行根通常以
     * {@code /data/user/0} 别名传入，maps 却是 {@code /data/data}。不绑定的话，guest 的 open
     * 会被翻译成 {@code <rootfs>/data/data/...}（通常不存在或只有骨架目录）并报 ENOENT。
     * 历史 maintenance proot 也在 {@code RecoveryToolchain} 里绑定同类自镜像。
     */
    private static String rootfsSelfMirror(File rootfsDir) {
      File canonical = rootfsDir;
      try {
        canonical = rootfsDir.getCanonicalFile();
      } catch (java.io.IOException ignored) {
        // 路径暂不可解析时退回传入路径，不阻断启动。
      }
      return canonical.getAbsolutePath() + ":" + canonical.getAbsolutePath();
    }

    @Override
    public void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir)
        throws java.io.IOException {
      prepare();
      File directory = nativeLibProot.getParentFile();
      String loader = "libprootloader.so";
      if (!new File(libDir, "libtalloc.so.2").isFile()
          || !new File(libDir, "libandroid-shmem.so").isFile())
        throw new java.io.IOException("PROOT_DEPENDENCIES_MISSING");
      if (seccompDisabled()) pb.environment().put("PROOT_NO_SECCOMP", "1");
      else pb.environment().remove("PROOT_NO_SECCOMP");
      pb.environment().put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
      pb.environment().put("PROOT_LOADER", new File(directory, loader).getAbsolutePath());
      File loader32 = new File(directory, "libprootloader32.so");
      if (loader32.isFile()) pb.environment().put("PROOT_LOADER_32", loader32.getAbsolutePath());
      else pb.environment().remove("PROOT_LOADER_32");
      pb.environment()
          .put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + directory.getAbsolutePath());
    }

    /** 与 applyEnv 同一来源：本次启动是否带 PROOT_NO_SECCOMP（无 seccomp 过滤的兼容档）。 */
    public boolean seccompDisabled() {
      RuntimeHostPorts.Settings settings =
          explicitSettings == null
              ? RuntimeHostPorts.fromOwner(context.getApplicationContext()).settings()
              : explicitSettings;
      return settings.disableProotSeccomp;
    }

    @Override
    public void prepare() throws java.io.IOException {
      if (!available()) throw new java.io.IOException("PROOT_EXECUTABLE_MISSING");
      String loader = "libprootloader.so";
      if (!new File(nativeLibProot.getParentFile(), loader).isFile())
        throw new java.io.IOException("PROOT_LOADER_MISSING");
    }
  }

  /** coderredlab/proroot，LD_PRELOAD 路径翻译，零 ptrace。 */
  class Proroot implements ContainerRuntime {
    private static final String COMPAT_PROXY = "libdsha-proroot-compat.so";
    private static final String COMPAT_ENTRY = "libdsha-proroot-linker.so";

    /** 五个 .so 都得在同一目录，启动器靠 /proc/self/exe 的 dirname 找同伴。 */
    public static final String[] LIBS = {
      "libproroot.so",
      "libproroot-runtime.so",
      "libproroot-linker.so",
      "libproroot-stub-loader.so",
      "libproroot-bridge.so",
    };

    private final Context ctx;
    private final File dir;
    private final boolean staticLoader;

    public Proroot(Context ctx, File dir) {
      this(
          ctx,
          dir,
          RuntimeHostPorts.fromOwner(ctx.getApplicationContext()).settings().staticLoader);
    }

    public Proroot(Context ctx, File dir, boolean staticLoader) {
      this.ctx = ctx;
      this.dir = dir;
      this.staticLoader = staticLoader;
    }

    /**
     * 存放目录：APK 的 jniLibs 提取目录（nativeLibraryDir）。
     * <b>不能放 filesDir</b>：Android 10+ 的 W^X 策略不允许从应用可写目录执行代码。
     */
    public static File defaultDir(Context ctx) {
      return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    @Override
    public String id() {
      return "proroot";
    }

    @Override
    public String displayName() {
      return com.deepseekharness.app.util.UiText.text("proroot（实验，零 ptrace 开销）");
    }

    @Override
    public boolean available() {
      for (String n : LIBS) {
        File f = new File(dir, n);
        if (!f.isFile() || f.length() == 0) return false;
      }
      File proxy = new File(dir, COMPAT_PROXY);
      File entry = new File(dir, COMPAT_ENTRY);
      return proxy.isFile() && proxy.length() > 0 && entry.isFile() && entry.length() > 0;
    }

    @Override
    public String unavailableReason() {
      List<String> missing = new ArrayList<>();
      for (String n : LIBS) {
        File f = new File(dir, n);
        if (!f.isFile() || f.length() == 0) missing.add(n);
      }
      File proxy = new File(dir, COMPAT_PROXY);
      if (!proxy.isFile() || proxy.length() == 0) missing.add(COMPAT_PROXY);
      File entry = new File(dir, COMPAT_ENTRY);
      if (!entry.isFile() || entry.length() == 0) missing.add(COMPAT_ENTRY);
      if (missing.isEmpty()) return "";
      return com.deepseekharness.app.util.UiText.format(
          "缺 %s 个运行时文件（%s 等）", missing.size(), missing.get(0));
    }

    @Override
    public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported) {
      return baseArgv(rootfsDir, hardlinkSupported, ctx.getFilesDir());
    }

    @Override
    public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported, File dataDomain) {
      List<String> argv = new ArrayList<>();
      argv.add(new File(dir, "libproroot.so").getAbsolutePath());
      argv.add(staticLoader ? "--static-loader" : "--no-static-loader");
      argv.add("-r");
      argv.add(rootfsDir.getAbsolutePath());
      argv.add("-0");
      argv.add("-w");
      argv.add("/root");
      // 运行根自镜像不在这里重复绑定：当前 v1.2.8 已把 -r 解析后的 canonical rootfs 绑进 guest
      // （见 util/GhHostRoot 与 build343 真机实测）；重复 -b 的 vendor 行为未验证，升级 proroot 时复核。
      for (Bind b : binds(com.deepseekharness.app.StorageAccess.granted(ctx))) {
        if (!new File(b.host()).exists()) {
          RuntimeHostPorts.fromOwner(ctx.getApplicationContext())
              .record("BIND_SKIPPED", b.host() + ": missing or inaccessible");
          continue;
        }
        argv.add("-b");
        argv.add(b.host() + ":" + b.guest());
      }
      File shm = shmDir();
      //noinspection ResultOfMethodCallIgnored
      shm.mkdirs();
      argv.add("-b");
      argv.add(shm.getAbsolutePath() + ":/dev/shm");
      argv.add("--link2symlink");
      if (dataDomain != null) UserDataBindings.append(argv, rootfsDir, dataDomain);
      return argv;
    }

    File shmDir() {
      return new File(ctx.getCacheDir(), "shm");
    }

    @Override
    public void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir)
        throws java.io.IOException {
      File proxy = new File(dir, COMPAT_PROXY);
      if (!proxy.isFile() || proxy.length() == 0)
        throw new java.io.IOException("PROROOT_COMPAT_PROXY_MISSING");
      File entry = new File(dir, COMPAT_ENTRY);
      if (!entry.isFile() || entry.length() == 0)
        throw new java.io.IOException("PROROOT_COMPAT_ENTRY_MISSING");
      pb.environment().put("PROROOT_TMP_DIR", tmpDir.getAbsolutePath());
      // Keep the original runtime a direct preload so its path hooks stay in lookup scope.
      pb.environment()
          .put("PROROOT_LIB_PATH", new File(dir, "libproroot-runtime.so").getAbsolutePath());
      pb.environment().put("PROROOT_LINKER_PATH", entry.getAbsolutePath());
      if (staticLoader)
        pb.environment()
            .put(
                "PROROOT_STUB_LOADER",
                new File(dir, "libproroot-stub-loader.so").getAbsolutePath());
      else pb.environment().remove("PROROOT_STUB_LOADER");
    }

    @Override
    public void prepare() throws Exception {
      for (String n : LIBS) {
        if (!new File(dir, n).isFile()) {
          throw new IllegalStateException(
              com.deepseekharness.app.util.UiText.format("proroot 运行时缺 %s", n));
        }
      }
      File proxy = new File(dir, COMPAT_PROXY);
      if (!proxy.isFile() || proxy.length() == 0)
        throw new java.io.IOException("PROROOT_COMPAT_PROXY_MISSING");
      File entry = new File(dir, COMPAT_ENTRY);
      if (!entry.isFile() || entry.length() == 0)
        throw new java.io.IOException("PROROOT_COMPAT_ENTRY_MISSING");
      //noinspection ResultOfMethodCallIgnored
      shmDir().mkdirs();
    }
  }

  /**
   * 两个运行时共用的 bind 列表，写在一处（避免「改了 proot 忘了改 proroot」）。
   * Bundled Termux Python 要用 Android 的 linker 和 APEX 库，所以映射 /system、/apex。
   *
   * <p>注意：<b>不</b>映射 /linkerconfig —— app 进程对它无权限（selinux），
   * proot 每次启动都会打 {@code can't sanitize binding "/linkerconfig": Permission denied}，
   * 污染终端/日志；而且 bind 失败 = 从没绑上，映射它是纯负收益。
   */
  record Bind(String host, String guest) {}

  /**
   * 容器挂载清单。公共存储（{@code /storage/emulated/0}）只在用户已授权文件访问时挂载；
   * 未授权时容器只能读写自己的运行根与私有目录，不能读手机文件。
   */
  static java.util.List<Bind> binds(boolean includePublicStorage) {
    java.util.List<Bind> list = new java.util.ArrayList<>();
    list.add(new Bind("/dev", "/dev"));
    list.add(new Bind("/dev/urandom", "/dev/random"));
    list.add(new Bind("/proc", "/proc"));
    list.add(new Bind("/sys", "/sys"));
    list.add(new Bind("/system", "/system"));
    list.add(new Bind("/apex", "/apex"));
    list.add(new Bind("/proc/self/fd", "/dev/fd"));
    if (includePublicStorage) {
      list.add(new Bind("/storage/emulated/0", "/sdcard"));
      list.add(new Bind("/storage/emulated/0", "/storage/emulated/0"));
    }
    return java.util.List.copyOf(list);
  }
}
