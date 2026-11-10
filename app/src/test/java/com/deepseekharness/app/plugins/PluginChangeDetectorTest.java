package com.deepseekharness.app.plugins;

import static org.junit.Assert.*;

import android.os.FileObserver;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;

/** 真实宿主临时目录与 inode；只替换 native 观察口和时钟，不需要手机或应用生命周期。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = android.app.Application.class,
    instrumentedPackages = {"com.deepseekharness.app.plugins"},
    shadows = {PluginChangeDetectorTest.HostStamp.class})
public final class PluginChangeDetectorTest {
  /** Adapt only the OS attribute call; all production traversal and observer logic still executes. */
  @Implements(value = PluginChangeDetector.class, isInAndroidSdk = false, looseSignatures = true)
  public static final class HostStamp {
    @Implementation
    protected static Object stamp(Path path) throws Exception {
      var node = PluginHostFiles.stat(path.toFile());
      if (node.type.equals("MISSING")) throw new java.nio.file.NoSuchFileException(path.toString());
      var type = Class.forName(PluginChangeDetector.class.getName() + "$Stamp");
      var constructor =
          type.getDeclaredConstructor(boolean.class, boolean.class, long.class, Object.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          node.type.equals("DIRECTORY"), node.type.equals("LINK"), node.device, node.key);
    }
  }

  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private ManualWorker worker;
  private FakeObservers nativeObservers;
  private AtomicInteger content, runtime;
  private PluginChangeDetector detector;
  private Path root;

  @Before
  public void setup() throws Exception {
    root = temporary.newFolder("host").toPath();
    worker = new ManualWorker();
    nativeObservers = new FakeObservers(worker);
    content = new AtomicInteger();
    runtime = new AtomicInteger();
    detector = detector(new PluginChangeDetector.Limits(128, 2048, 32, 1000));
  }

  @After
  public void cleanup() {
    try {
      detector.close();
      worker.runDue();
      assertEquals(0, nativeObservers.active());
    } finally {
      PluginHostFiles.close();
    }
  }

  private PluginChangeDetector detector(PluginChangeDetector.Limits limits) {
    return new PluginChangeDetector(
        () -> {
          assertTrue(worker.running);
          content.incrementAndGet();
        },
        () -> {
          assertTrue(worker.running);
          runtime.incrementAndGet();
        },
        worker,
        () -> worker.now,
        nativeObservers::start,
        limits);
  }

  private PluginChangeDetector.WatchPath path(
      Path path, PluginChangeDetector.Kind kind, boolean recursive) {
    return new PluginChangeDetector.WatchPath(root.toFile(), path.toFile(), kind, recursive);
  }

  private void bind(PluginChangeDetector.WatchPath... paths) {
    detector.bind(List.of(paths), "identity-1");
    detector.foreground(true);
    worker.runDue();
    content.set(0);
    runtime.set(0);
  }

  @Test
  public void constructionAndBindingDoNoNativeWorkOnCallerAndIdleDoesNotPoll() throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    detector.bind(List.of(path(plugins, PluginChangeDetector.Kind.CONTENT, true)), "one");
    assertEquals(0, nativeObservers.started.size());
    worker.runDue();
    assertEquals(0, nativeObservers.active());
    detector.foreground(true);
    assertEquals(0, nativeObservers.active());
    worker.runDue();
    assertNotNull(nativeObservers.latest(plugins));
    assertTrue(content.get() > 0);
    int created = nativeObservers.started.size(), signals = content.get();
    worker.advance(600000);
    assertEquals(created, nativeObservers.started.size());
    assertEquals(signals, content.get());
    assertEquals(0, worker.pending());
  }

  @Test
  public void deepContentWriteInvalidatesWithoutRebuildingOrReadingTheFile() throws Exception {
    Path deep = Files.createDirectories(root.resolve("plugins/pkg/src/deep/one/two/three"));
    bind(path(root.resolve("plugins"), PluginChangeDetector.Kind.CONTENT, true));
    int created = nativeObservers.started.size();
    nativeObservers.latest(deep).emit(FileObserver.CLOSE_WRITE, "entry.js");
    worker.runDue();
    assertEquals(1, content.get());
    assertEquals(0, runtime.get());
    assertEquals(created, nativeObservers.started.size());
    assertEquals(0, worker.pending());
  }

  @Test
  public void eventStormUsesOneQueuedInvalidation() throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    for (int i = 0; i < 10000; i++)
      nativeObservers.latest(plugins).emit(FileObserver.MODIFY, "entry.js");
    assertEquals(1, worker.pending());
    worker.runDue();
    assertEquals(1, content.get());
    assertEquals(0, worker.pending());
  }

  @Test
  public void runtimeHeartbeatAtomicReplacementNeverQueuesFullContentScan() throws Exception {
    Path heartbeat = root.resolve(".dsha-web-activity.json");
    bind(
        path(root, PluginChangeDetector.Kind.SOURCE_IDENTITY, false),
        path(heartbeat, PluginChangeDetector.Kind.RUNTIME_PROJECTION, false),
        path(root.resolve("plugin-activations.json"), PluginChangeDetector.Kind.CONTENT, false));
    int created = nativeObservers.started.size();
    FakeWatch parent = nativeObservers.latest(root);
    for (int i = 0; i < 10; i++) {
      parent.emit(FileObserver.CREATE, ".dsha-web-activity.json.tmp");
      parent.emit(FileObserver.CLOSE_WRITE, ".dsha-web-activity.json.tmp");
      parent.emit(FileObserver.MOVED_TO, heartbeat.getFileName().toString());
      worker.runDue();
      worker.advance(worker.now + 2000);
    }
    assertEquals(0, content.get());
    assertEquals(10, runtime.get());
    assertEquals(created, nativeObservers.started.size());
    parent.emit(FileObserver.MOVED_TO, "plugin-activations.json");
    worker.runDue();
    assertEquals(1, content.get());
  }

  @Test
  public void rootIdentityStillInvalidatesWhenWatchedRootMoves() throws Exception {
    bind(path(root, PluginChangeDetector.Kind.SOURCE_IDENTITY, false));
    nativeObservers.latest(root).emit(FileObserver.MOVE_SELF, null);
    worker.runDue();
    assertEquals(1, content.get());
    worker.advance(750);
    assertNotNull(nativeObservers.latest(root));
  }

  @Test
  public void replacedDirectoryRebindsSamePathAndFindsNewDeepTree() throws Exception {
    Path plugins =
        Files.createDirectories(root.resolve("plugins/old/deep")).getParent().getParent();
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    FakeWatch previous = nativeObservers.latest(plugins);
    Files.move(plugins, root.resolve("previous-plugins"));
    Path fresh = Files.createDirectories(plugins.resolve("new/deep"));
    nativeObservers
        .latest(root)
        .emit(FileObserver.MOVED_TO | PluginChangeDetector.IS_DIRECTORY, "plugins");
    worker.runDue();
    worker.advance(750);
    assertTrue(previous.stopped);
    assertNotSame(previous, nativeObservers.latest(plugins));
    assertNotNull(nativeObservers.latest(fresh));
    content.set(0);
    nativeObservers.latest(fresh).emit(FileObserver.CLOSE_WRITE, "entry.js");
    worker.runDue();
    assertEquals(1, content.get());
  }

  @Test
  public void directoryReplacedInsideNativeRegistrationGetsCompensatingRebind() throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins/old")).getParent();
    nativeObservers.replaceDuringStart = plugins;
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    assertTrue(detector.coverageLimited());
    assertNull(nativeObservers.latest(plugins));
    assertTrue(worker.pending() > 0);
    worker.advance(750);
    assertNotNull(nativeObservers.latest(plugins.resolve("replacement/deep")));
    assertFalse(detector.coverageLimited());
    assertEquals(0, worker.pending());
  }

  @Test
  public void missingAncestorsAreObservedAndRecreatedTreeIsBound() throws Exception {
    Path requested = root.resolve("missing/plugins");
    bind(path(requested, PluginChangeDetector.Kind.CONTENT, true));
    assertEquals(1, nativeObservers.active());
    Path deep = Files.createDirectories(requested.resolve("pkg/src"));
    nativeObservers
        .latest(root)
        .emit(FileObserver.CREATE | PluginChangeDetector.IS_DIRECTORY, "missing");
    worker.runDue();
    worker.advance(750);
    assertNotNull(nativeObservers.latest(deep));
  }

  @Test
  public void invalidatedObserverIsReboundEvenWhenInodeDidNotChange() throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    FakeWatch previous = nativeObservers.latest(plugins);
    previous.emit(PluginChangeDetector.IGNORED, null);
    worker.runDue();
    assertTrue(previous.stopped);
    worker.advance(750);
    assertNotSame(previous, nativeObservers.latest(plugins));
    content.set(0);
    nativeObservers.latest(plugins).emit(PluginChangeDetector.OVERFLOW, null);
    worker.runDue();
    assertEquals(1, content.get());
  }

  @Test
  public void retiredWatchLateInvalidationCannotStartAnotherRebind() throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    FakeWatch previous = nativeObservers.latest(plugins);
    previous.emit(PluginChangeDetector.IGNORED, null);
    worker.runDue();
    worker.advance(750);
    content.set(0);
    int created = nativeObservers.started.size();
    previous.emit(PluginChangeDetector.IGNORED, null);
    previous.emit(FileObserver.MODIFY, "stale.js");
    worker.runDue();
    worker.advance(1500);
    assertEquals(0, content.get());
    assertEquals(created, nativeObservers.started.size());
    assertEquals(0, worker.pending());
  }

  @Test
  public void directoriesCreatedWhileObserversAreBeingBuiltSurviveNextRepairBatch()
      throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    Path fresh = Files.createDirectories(plugins.resolve("new"));
    nativeObservers.extendDuringStart = fresh;
    nativeObservers
        .latest(plugins)
        .emit(FileObserver.CREATE | PluginChangeDetector.IS_DIRECTORY, "new");
    worker.runDue();
    worker.advance(750);
    assertNull(nativeObservers.latest(fresh.resolve("late/deep")));
    assertTrue(worker.pending() > 0);
    worker.advance(1500);
    assertNotNull(nativeObservers.latest(fresh.resolve("late/deep")));
    assertTrue(content.get() > 0);
    assertEquals(0, worker.pending());
  }

  @Test
  public void backgroundStopsObserversAndResumeCompensatesWithoutAnyBackgroundScan()
      throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    FakeWatch old = nativeObservers.latest(plugins);
    detector.foreground(false);
    old.emit(FileObserver.MODIFY, "late.js");
    worker.runDue();
    assertEquals(0, nativeObservers.active());
    assertEquals(0, content.get());
    Files.createDirectories(plugins.resolve("created-while-away/deep"));
    worker.advance(600000);
    assertEquals(0, content.get());
    detector.foreground(true);
    worker.runDue();
    assertTrue(content.get() > 0);
    assertNotNull(nativeObservers.latest(plugins.resolve("created-while-away/deep")));
  }

  @Test
  public void changedIdentityRejectsLateOldCallbacksAndRebinds() throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    PluginChangeDetector.WatchPath input = path(plugins, PluginChangeDetector.Kind.CONTENT, true);
    bind(input);
    FakeWatch old = nativeObservers.latest(plugins);
    detector.bind(List.of(input), "identity-2");
    old.emit(FileObserver.MODIFY, "late.js");
    worker.runDue();
    assertEquals(1, content.get());
    worker.advance(750);
    assertTrue(old.stopped);
    assertNotSame(old, nativeObservers.latest(plugins));
    content.set(0);
    detector.bind(List.of(input), "identity-2");
    worker.runDue();
    assertEquals(0, content.get());
  }

  @Test
  public void symlinkIntoExternalTreeOrCycleIsNeverTraversed() throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    Path outside = temporary.newFolder("external").toPath();
    Path externalDeep = Files.createDirectories(outside.resolve("private/deep"));
    PluginHostFiles.symlink(plugins.resolve("external"), outside);
    PluginHostFiles.symlink(plugins.resolve("cycle"), plugins);
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    assertNull(nativeObservers.latest(externalDeep));
    assertNull(nativeObservers.latest(plugins.resolve("external")));
    assertNull(nativeObservers.latest(plugins.resolve("cycle")));
    assertEquals(2, nativeObservers.active());
  }

  @Test
  public void symlinkInRequestedParentChainIsRejectedAndReported() throws Exception {
    Path outside = temporary.newFolder("external").toPath();
    Files.createDirectories(outside.resolve("plugins"));
    PluginHostFiles.symlink(root.resolve("alias"), outside);
    bind(path(root.resolve("alias/plugins"), PluginChangeDetector.Kind.CONTENT, true));
    assertEquals(1, nativeObservers.active());
    assertTrue(detector.coverageLimited());
  }

  @Test
  public void finiteBudgetsReportIncompleteCoverageAndKeepOtherTopLevelEntries() throws Exception {
    detector.close();
    worker.runDue();
    worker = new ManualWorker();
    nativeObservers = new FakeObservers(worker);
    detector = detector(new PluginChangeDetector.Limits(4, 6, 3, 1000));
    Path deep = Files.createDirectories(root.resolve("plugins/a/b/c/d/e/f/g"));
    Path profiles = Files.createDirectories(root.resolve("profiles"));
    bind(
        path(root.resolve("plugins"), PluginChangeDetector.Kind.CONTENT, true),
        path(profiles, PluginChangeDetector.Kind.CONTENT, true));
    assertTrue(detector.coverageLimited());
    assertTrue(nativeObservers.active() <= 4);
    assertNotNull(nativeObservers.latest(profiles));
    assertNull(nativeObservers.latest(deep));
    worker.advance(100000);
    assertEquals(0, worker.pending());
  }

  @Test
  public void nativeRegistrationFailureIsLimitedAndResumeCanRecover() throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    nativeObservers.failure = plugins;
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    assertTrue(detector.coverageLimited());
    detector.foreground(false);
    worker.runDue();
    nativeObservers.failure = null;
    worker.advance(750);
    detector.foreground(true);
    worker.runDue();
    assertNotNull(nativeObservers.latest(plugins));
    assertFalse(detector.coverageLimited());
  }

  @Test
  public void explicitOutsidePathFailsBeforeAnyFilesystemOrNativeWork() {
    try {
      new PluginChangeDetector.WatchPath(
          root.toFile(),
          root.resolve("../outside").toFile(),
          PluginChangeDetector.Kind.CONTENT,
          true);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertEquals(0, nativeObservers.started.size());
  }

  private AtomicInteger useReconcileCallback() {
    detector.close();
    worker.runDue();
    worker = new ManualWorker();
    nativeObservers = new FakeObservers(worker);
    AtomicInteger reconcile = new AtomicInteger();
    detector =
        new PluginChangeDetector(
            content::incrementAndGet,
            runtime::incrementAndGet,
            reconcile::incrementAndGet,
            worker,
            () -> worker.now,
            nativeObservers::start,
            new PluginChangeDetector.Limits(128, 2048, 32, 1000));
    return reconcile;
  }

  @Test
  public void thirdCallbackSeparatesBindingAndShortForegroundResumeFromRealDirty()
      throws Exception {
    AtomicInteger reconcile = useReconcileCallback();
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    assertEquals(0, content.get());
    assertEquals(2, reconcile.get());
    detector.foreground(false);
    worker.runDue();
    detector.foreground(true);
    worker.runDue();
    worker.advance(750);
    assertEquals(0, content.get());
    assertEquals(4, reconcile.get());
    nativeObservers.latest(plugins).emit(FileObserver.CLOSE_WRITE, "real.js");
    worker.runDue();
    assertEquals(1, content.get());
    assertEquals(4, reconcile.get());
  }

  @Test
  public void knownDirtyQueuedImmediatelyBeforePauseCannotBeDowngradedToReconcile()
      throws Exception {
    useReconcileCallback();
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    nativeObservers.latest(plugins).emit(FileObserver.CLOSE_WRITE, "real.js");
    detector.foreground(false);
    worker.runDue();
    assertEquals(0, content.get());
    detector.foreground(true);
    worker.runDue();
    assertEquals(1, content.get());
    worker.advance(750);
    assertEquals(1, content.get());
  }

  @Test
  public void registrationWindowIdentityChangeStillUsesDirtyWithSeparateReconcile()
      throws Exception {
    useReconcileCallback();
    Path plugins = Files.createDirectories(root.resolve("plugins/old")).getParent();
    nativeObservers.replaceDuringStart = plugins;
    detector.bind(List.of(path(plugins, PluginChangeDetector.Kind.CONTENT, true)), "identity");
    detector.foreground(true);
    worker.runDue();
    assertTrue(content.get() > 0);
    worker.advance(750);
    assertNotNull(nativeObservers.latest(plugins.resolve("replacement/deep")));
  }

  @Test
  public void closeIsIdempotentAndIgnoresLateEvents() throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    bind(path(plugins, PluginChangeDetector.Kind.CONTENT, true));
    FakeWatch previous = nativeObservers.latest(plugins);
    detector.close();
    detector.close();
    previous.emit(FileObserver.MODIFY, "late.js");
    worker.runDue();
    assertEquals(0, content.get());
    assertTrue(worker.closed);
  }

  private static final class FakeObservers {
    final ManualWorker worker;
    final List<FakeWatch> started = new ArrayList<>();
    Path failure, extendDuringStart, replaceDuringStart;

    FakeObservers(ManualWorker worker) {
      this.worker = worker;
    }

    PluginChangeDetector.Watch start(File directory, PluginChangeDetector.EventSink sink)
        throws IOException {
      assertTrue("native 观察器必须在后台构建", worker.running);
      if (directory.toPath().equals(failure))
        throw new IOException("synthetic registration failure");
      FakeWatch watch = new FakeWatch(directory.toPath(), sink, worker);
      started.add(watch);
      if (directory.toPath().equals(replaceDuringStart)) {
        replaceDuringStart = null;
        Files.move(directory.toPath(), directory.toPath().resolveSibling("swapped-during-start"));
        Files.createDirectories(directory.toPath().resolve("replacement/deep"));
      }
      if (directory.toPath().equals(extendDuringStart)) {
        extendDuringStart = null;
        Files.createDirectories(directory.toPath().resolve("late/deep"));
        sink.event(FileObserver.CREATE | PluginChangeDetector.IS_DIRECTORY, "late");
      }
      return watch;
    }

    FakeWatch latest(Path path) {
      for (int i = started.size() - 1; i >= 0; i--) {
        FakeWatch watch = started.get(i);
        if (watch.path.equals(path) && !watch.stopped) return watch;
      }
      return null;
    }

    int active() {
      return (int) started.stream().filter(watch -> !watch.stopped).count();
    }
  }

  private static final class FakeWatch implements PluginChangeDetector.Watch {
    final Path path;
    final PluginChangeDetector.EventSink sink;
    final ManualWorker worker;
    boolean stopped;

    FakeWatch(Path path, PluginChangeDetector.EventSink sink, ManualWorker worker) {
      this.path = path;
      this.sink = sink;
      this.worker = worker;
    }

    void emit(int event, String child) {
      sink.event(event, child);
    }

    @Override
    public void stop() {
      assertTrue(worker.running);
      stopped = true;
    }
  }

  private static final class ManualWorker implements PluginChangeDetector.Scheduler {
    long now, sequence;
    boolean running, closed;
    final List<Task> tasks = new ArrayList<>();

    @Override
    public void execute(Runnable work) {
      schedule(work, 0);
    }

    @Override
    public PluginChangeDetector.Cancellation schedule(Runnable work, long delay) {
      if (closed) throw new AssertionError("关闭后不能排队");
      Task task = new Task(now + delay, sequence++, work);
      tasks.add(task);
      return () -> task.cancelled = true;
    }

    @Override
    public void close() {
      closed = true;
    }

    int pending() {
      return (int) tasks.stream().filter(task -> !task.cancelled).count();
    }

    void advance(long time) {
      now = time;
      runDue();
    }

    void runDue() {
      for (int i = 0; i < 10000; i++) {
        Task next =
            tasks.stream()
                .filter(task -> !task.cancelled && task.at <= now)
                .min(
                    Comparator.comparingLong((Task task) -> task.at)
                        .thenComparingLong(task -> task.sequence))
                .orElse(null);
        if (next == null) return;
        tasks.remove(next);
        running = true;
        try {
          next.work.run();
        } finally {
          running = false;
        }
      }
      throw new AssertionError("检测到无界重排队");
    }
  }

  private static final class Task {
    final long at, sequence;
    final Runnable work;
    boolean cancelled;

    Task(long at, long sequence, Runnable work) {
      this.at = at;
      this.sequence = sequence;
      this.work = work;
    }
  }
}
