package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.backup.RuntimeDescriptor;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.WebProcSel;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Real controller/intent and environment gates; only slow I/O and remote close are adapters. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = WebLifecycleResponsivenessTest.App.class)
public final class WebLifecycleResponsivenessTest {
  /** Only lstat metadata for private empty host directories; no guest/transaction I/O is mocked. */
  @Implements(Os.class)
  public static final class HostMetadata {
    @Implementation
    protected static StructStat lstat(String value) throws ErrnoException {
      Path path = Path.of(value);
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        throw new ErrnoException("lstat", android.system.OsConstants.ENOENT);
      int mode = Files.isSymbolicLink(path) ? 0120000 : Files.isDirectory(path) ? 0040000 : 0100000;
      return new StructStat(1, value.hashCode(), mode | 0700, 1, 0, 0, 0, 0, 0, 0, 0, 4096, 0);
    }
  }

  public static class App extends DshaApp {

    @Override
    public void onCreate() {}
  }

  static final class Pending extends ScheduledThreadPoolExecutor {
    static final class Deferred extends FutureTask<Void> {
      Deferred(Runnable work) {
        super(work, null);
      }

      void complete() {
        set(null);
      }
    }

    final List<Runnable> queued = new CopyOnWriteArrayList<>();

    Pending() {
      super(1);
    }

    @Override
    public void execute(Runnable work) {
      queued.add(work);
    }

    @Override
    public Future<?> submit(Runnable work) {
      var task = new Deferred(work);
      queued.add(task);
      return task;
    }
  }

  static final class Screens implements WebLifecycleController.ScreenLifetime {
    final CountDownLatch closeEntered = new CountDownLatch(1), releaseClose = new CountDownLatch(1);
    final AtomicInteger revokes = new AtomicInteger(), closes = new AtomicInteger();
    volatile Thread closeThread;

    @Override
    public void revoke() {
      revokes.incrementAndGet();
    }

    @Override
    public void stop() {
      closes.incrementAndGet();
      closeThread = Thread.currentThread();
      closeEntered.countDown();
      try {
        releaseClose.await(3, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("synthetic unknown remote close");
    }
  }

  static final class Boot extends ProotBootstrap {
    final CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger reads = new AtomicInteger();
    volatile boolean slow, ready;
    Runnable pluginChange;
    final AtomicInteger pluginCalls = new AtomicInteger();
    final RuntimeDescriptor descriptor;

    Boot(Context c) throws Exception {
      super(c);
      var m = new LinkedHashMap<String, Object>();
      m.put("version", 1L);
      m.put("baseVersion", "10");
      m.put("dshVersion", "0.2.1-rc.2");
      m.put("launcherContract", "TEST");
      m.put("launcherInputs", Map.of());
      m.put("bridgeProtocol", 1L);
      m.put("inputs", Map.of());
      m.put("dataRead", List.of("v1"));
      m.put("dataWrite", "v1");
      m.put("runtimeId", RuntimeDescriptor.calculateId(m));
      descriptor = new RuntimeDescriptor(m);
    }

    @Override
    public boolean isEnvironmentReady() {
      reads.incrementAndGet();
      reading.countDown();
      if (slow)
        try {
          release.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      return ready;
    }

    @Override
    public RuntimeDescriptor expectedRuntimeDescriptor() {
      return descriptor;
    }

    @Override
    public RuntimeDescriptor installedRuntimeDescriptor() {
      return descriptor;
    }

    @Override
    public String setPluginEnabled(String name, boolean enable) {
      pluginCalls.incrementAndGet();
      if (pluginChange != null) pluginChange.run();
      return "BUILTIN_REGISTER_OK";
    }
  }

  App app;
  Boot boot;
  Screens screens;
  HarnessController controller;
  HarnessSessionState state;
  Pending pending;

  @Before
  public void create() throws Exception {
    app = (App) RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getFilesDir().getAbsolutePath();
    state = app.harnessSession();
    state.io.shutdownNow();
    pending = new Pending();
    var f = HarnessSessionState.class.getDeclaredField("io");
    f.setAccessible(true);
    f.set(state, pending);
    screens = new Screens();
    boot = new Boot(app);
    controller = new HarnessController(app, boot);
    var web = HarnessController.class.getDeclaredField("web");
    web.setAccessible(true);
    web.set(controller, new WebLifecycleController(controller, state, screens));
  }

  @After
  public void close() throws Exception {
    if (boot != null) boot.release.countDown();
    if (screens != null) screens.releaseClose.countDown();
    if (pending != null) pending.shutdownNow();
  }

  @Test
  public void adbReconnectBusyRejectsBeforeAnyReadinessIo() throws Exception {
    try (var lease = EnvironmentTaskGate.tryAcquire("ADB reconnect")) {
      assertNotNull(lease);
      assertFalse(controller.startWeb(x -> {}));
      assertEquals("busy ADB must not enter disk preflight", 0, boot.reads.get());
    }
  }

  @Test
  public void slowStartupPreflightDoesNotHoldTheLifecycleMonitor() throws Exception {
    boot.slow = true;
    Thread start = new Thread(() -> controller.startWeb(x -> {}));
    CountDownLatch queried = new CountDownLatch(1);
    Thread query =
        new Thread(
            () -> {
              controller.isStarting();
              queried.countDown();
            });
    start.start();
    assertTrue(boot.reading.await(2, TimeUnit.SECONDS));
    query.start();
    boolean responsive = queried.await(250, TimeUnit.MILLISECONDS);
    boot.release.countDown();
    start.join(2000);
    query.join(2000);
    assertTrue("UI state query waited for disk preflight", responsive);
  }

  @Test
  public void stopIntentCanWinWhileStartupPreflightIsStillReading() throws Exception {
    boot.slow = true;
    AtomicBoolean accepted = new AtomicBoolean();
    Thread start = new Thread(() -> accepted.set(controller.startWeb(x -> {})));
    CountDownLatch stopped = new CountDownLatch(1);
    start.start();
    assertTrue(boot.reading.await(2, TimeUnit.SECONDS));
    Thread stop =
        new Thread(
            () -> {
              state.lifecycle.beginStop();
              stopped.countDown();
            });
    stop.start();
    boolean responsive = stopped.await(250, TimeUnit.MILLISECONDS);
    boot.release.countDown();
    start.join(2000);
    stop.join(2000);
    assertTrue("Stop could not revoke a slow admission", responsive);
    assertFalse(accepted.get());
    assertTrue(controller.isUserStopped());
    assertTrue(pending.queued.isEmpty());
  }

  @Test
  public void asyncStopReturnsBeforeRemoteCoreCloseAndKeepsUnknownStopProof() throws Exception {
    CountDownLatch returned = new CountDownLatch(1);
    Thread caller =
        new Thread(
            () -> {
              controller.stopWeb(x -> {});
              returned.countDown();
            });
    caller.start();
    boolean fast = returned.await(250, TimeUnit.MILLISECONDS);
    if (!fast) {
      screens.releaseClose.countDown();
      caller.join(2000);
    }
    assertTrue("Async stop performed remote close on caller", fast);
    assertTrue(controller.isStopping());
    assertFalse(controller.lastStopResult().stopped());
    assertEquals(0, screens.closes.get());
    assertTrue(screens.revokes.get() > 0);
    assertEquals(1, pending.queued.size());
    Thread worker = new Thread(pending.queued.remove(0), "fixture-stop-worker");
    worker.start();
    assertTrue(screens.closeEntered.await(2, TimeUnit.SECONDS));
    assertNotSame(caller, screens.closeThread);
    assertFalse(controller.lastStopResult().stopped());
    screens.releaseClose.countDown();
    worker.join(2000);
    assertFalse(worker.isAlive());
    assertFalse(controller.lastStopResult().stopped());
    assertFalse(controller.lastStopError().isBlank());
    assertTrue(controller.isUserStopped());
  }

  @Test
  public void duplicateStopQueuesOnlyOneRemoteClose() throws Exception {
    controller.stopWeb(x -> {});
    controller.stopWeb(x -> {});
    assertEquals(1, pending.queued.size());
    assertEquals(0, screens.closes.get());
    assertTrue(controller.isStopping());
  }

  @Test
  public void staleQueuedManualAndAutomaticRequestsRejectBeforePreflight() throws Exception {
    long old = controller.getWebGeneration();
    state.lifecycle.beginStop();
    state.lifecycle.finishStop(state.lifecycle.generation());
    assertFalse(controller.startWebIfUnchanged(old, true, x -> {}));
    assertFalse(controller.startWebIfUnchanged(old, false, x -> {}));
    assertEquals(0, boot.reads.get());
  }

  @Test
  public void currentManualStartCanPassUserStoppedIntentButAutomaticCannot() throws Exception {
    state.lifecycle.beginStop();
    state.lifecycle.finishStop(state.lifecycle.generation());
    long expected = controller.getWebGeneration();
    assertFalse(controller.startWebIfUnchanged(expected, false, x -> {}));
    assertEquals(0, boot.reads.get());
    assertFalse(controller.startWebIfUnchanged(expected, true, x -> {}));
    assertEquals("explicit Start reaches readiness preflight", 1, boot.reads.get());
  }

  private void readyProfile() throws Exception {
    assertTrue(new File(boot.getRootfsDir(), "root/.dsh").mkdirs());
    boot.ready = true;
    assertFalse(StartupRepairs.pending(app));
    assertFalse(MaintenanceCoordinator.pending(controller));
  }

  @Test
  @Config(shadows = HostMetadata.class)
  public void readyPreflightRechecksGenerationAfterStopWins() throws Exception {
    readyProfile();
    boot.slow = true;
    AtomicBoolean accepted = new AtomicBoolean();
    Thread start = new Thread(() -> accepted.set(controller.startWeb(x -> {})));
    start.start();
    assertTrue(boot.reading.await(2, TimeUnit.SECONDS));
    state.lifecycle.beginStop();
    state.lifecycle.finishStop(state.lifecycle.generation());
    boot.release.countDown();
    start.join(2000);
    assertFalse(start.isAlive());
    assertFalse("valid preflight must not recreate authority after Stop", accepted.get());
    assertTrue(controller.isUserStopped());
    assertTrue(pending.queued.isEmpty());
    assertFalse(EnvironmentTaskGate.isBusy());
  }

  @Test
  @Config(shadows = HostMetadata.class)
  public void firstSetupManualTicketCanPassMaintenanceStopSentinelAndStillRejectALaterStop()
      throws Exception {
    readyProfile();
    state.lifecycle.beginStop();
    state.lifecycle.finishStop(state.lifecycle.generation());
    File sentinel = new File(boot.getRootfsDir(), WebProcSel.pidFileRel(WebProcSel.STOP_SENTINEL));
    assertTrue(sentinel.createNewFile());
    long afterMaintenance = controller.getWebGeneration();
    assertFalse(controller.startWebIfUnchanged(afterMaintenance, false, x -> {}));
    assertTrue(controller.startWebIfUnchanged(afterMaintenance, true, x -> {}));
    assertEquals(1, pending.queued.size());
    assertTrue(controller.isStarting());
    assertFalse(controller.isUserStopped());
    Runnable staleStartup = pending.queued.remove(0);
    state.lifecycle.beginStop();
    state.lifecycle.finishStop(state.lifecycle.generation());
    staleStartup.run();
    assertFalse(EnvironmentTaskGate.isBusy());
    assertTrue(controller.isUserStopped());
    assertFalse(controller.startWebIfUnchanged(afterMaintenance, true, x -> {}));
    assertTrue(pending.queued.isEmpty());
  }

  @Test
  @Config(shadows = HostMetadata.class)
  public void queuedRecoveryCannotAdoptAnUnrelatedLaterGeneration() throws Exception {
    readyProfile();
    long original = state.lifecycle.beginStart(false, false);
    state.lifecycle.finishStart(original);
    state.diagnostics.begin(original, false);
    state.diagnostics.issue(original, "broken-user-plugin", "synthetic plugin failure");
    assertTrue(controller.recoverWebIfUnchanged(original, true, "broken-user-plugin", x -> {}));
    assertEquals(2, pending.queued.size());
    // Complete this stop boundary without remote I/O. A later explicit Stop arrives during
    // the separately-authorized disable operation, before its queued recovery can restart.
    Pending.Deferred queuedStop = (Pending.Deferred) pending.queued.remove(0);
    Runnable continuation = pending.queued.remove(0);
    long recoveryStop = controller.getWebGeneration();
    state.lifecycle.finishStop(recoveryStop);
    queuedStop.complete();
    boot.pluginChange =
        () -> {
          state.lifecycle.beginStop();
          state.lifecycle.finishStop(state.lifecycle.generation());
        };
    continuation.run();
    assertTrue(pending.queued.isEmpty());
    assertEquals(1, boot.pluginCalls.get());
    assertEquals(0, boot.reads.get());
    assertTrue(controller.isUserStopped());
  }

  @Test
  public void staleQueuedSafeRecoveryCannotUndoALaterStop() throws Exception {
    long old = controller.getWebGeneration();
    state.lifecycle.beginStop();
    assertFalse(controller.recoverWebIfUnchanged(old, true, null, x -> {}));
    assertTrue(pending.queued.isEmpty());
    assertEquals(0, screens.revokes.get());
  }
}
