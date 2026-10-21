package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import com.deepseekharness.app.util.VirtualScreenLifecycleState;
import java.io.File;
import java.net.ServerSocket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real manager/journal, controlled typed channel and scheduler; no Android exit claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenCloseReconcileTest {
  @org.junit.Before
  public void standardVirtualScreenFeatureOnly() {
    org.junit.Assume.assumeFalse(com.deepseekharness.app.BuildConfig.LOW_ANDROID);
  }

  private static final String BOOT = "11111111-2222-3333-4444-555555555555";
  private static final VirtualScreenCoreIdentity ID =
      VirtualScreenCoreIdentity.parse(
          "123:456:2000:" + BOOT + ":aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
    Task(Runnable work) {
      super(work, null);
    }

    public long getDelay(TimeUnit unit) {
      return 0;
    }

    public int compareTo(Delayed other) {
      return 0;
    }
  }

  private static final class ManualWorker extends ScheduledThreadPoolExecutor {
    final ArrayDeque<Task> tasks = new ArrayDeque<>();
    final List<Long> delays = new ArrayList<>();

    ManualWorker() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable work, long delay, TimeUnit unit) {
      Task task = new Task(work);
      if (delay == 0) {
        task.run();
        return task;
      }
      tasks.add(task);
      delays.add(unit.toMillis(delay));
      return task;
    }

    void next() throws Exception {
      Task task = tasks.remove();
      task.run();
      if (!task.isCancelled()) task.get();
    }
  }

  private static Object get(Object owner, String name) throws Exception {
    var field = VirtualScreenManager.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    var field = VirtualScreenManager.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private final class Fixture implements AutoCloseable {
    final File root = temporary.newFolder();
    final ManualWorker worker = new ManualWorker();
    final ArrayDeque<String> answers = new ArrayDeque<>();
    final List<String> commands = new ArrayList<>();
    boolean available = true;
    Runnable duringProbe;
    final Context context =
        new ContextWrapper(RuntimeEnvironment.getApplication()) {
          @Override
          public File getFilesDir() {
            return root;
          }

          @Override
          public ApplicationInfo getApplicationInfo() {
            ApplicationInfo info = new ApplicationInfo();
            info.sourceDir = new File(root, "base.apk").getAbsolutePath();
            return info;
          }
        };
    final VirtualScreenChannel channel =
        new VirtualScreenChannel() {
          public String id() {
            return "shizuku";
          }

          public boolean available(Context ignored) {
            return available;
          }

          public String start(Context ignored, String command) {
            assertTrue(command.contains("--probe-exit --identity"));
            assertTrue(command.contains(ID.encode()));
            assertFalse(command.contains("--launch"));
            assertFalse(command.contains("/vscreen/close"));
            commands.add(command);
            if (duringProbe != null) {
              Runnable callback = duringProbe;
              duringProbe = null;
              callback.run();
            }
            return answers.remove();
          }
        };
    final VirtualScreenManager manager =
        new VirtualScreenManager(
            context,
            () -> 100,
            new VirtualScreenManager.Visuals() {
              public void present() {}

              public void stopped(long fence) {}
            },
            new VirtualScreenManager.Channels() {
              public VirtualScreenChannel choose(Context ignored) {
                return channel;
              }

              public VirtualScreenChannel find(String id) {
                return "shizuku".equals(id) ? channel : null;
              }
            },
            () -> BOOT,
            worker);

    Fixture() throws Exception {
      int closedPort;
      try (ServerSocket socket = new ServerSocket(0)) {
        closedPort = socket.getLocalPort();
      }
      VirtualScreenHandshake handle = new VirtualScreenHandshake(closedPort, "a".repeat(48), ID);
      set(manager, "core", handle);
      set(manager, "token", handle.token);
      set(manager, "port", handle.port);
      set(manager, "channel", "shizuku");
      set(manager, "attemptBoot", BOOT);
      set(manager, "lifecycleState", VirtualScreenLifecycleState.READY);
    }

    void answer(String status) {
      answers.add("DSHA_VSCREEN_EXIT_PROOF 1 " + ID.encode() + " " + status + "\n[EXIT=0]");
    }

    void beginUnknown() {
      answer("UNKNOWN");
      JSONObject result = manager.close();
      assertFalse(result.optBoolean("ok"));
      assertEquals("UNKNOWN", result.optString("coreExitProof"));
      assertEquals(VirtualScreenLifecycleState.CLOSE_UNKNOWN, manager.lifecycleState());
      assertEquals(1, worker.tasks.size());
    }

    JSONObject journal() throws Exception {
      return new VirtualScreenSessionJournal(root).load();
    }

    public void close() {
      worker.shutdownNow();
    }
  }

  @Test
  public void laterExactExitedPersistsStoppedBeforeClearingPending() throws Exception {
    try (Fixture f = new Fixture()) {
      f.beginUnknown();
      f.answer("EXITED");
      f.worker.next();
      assertEquals(VirtualScreenLifecycleState.STOPPED, f.manager.lifecycleState());
      assertNull(get(f.manager, "pendingCleanup"));
      assertEquals("STOPPED", f.journal().getString("state"));
      assertEquals("", f.journal().getString("handle"));
      assertEquals(2, f.commands.size());
      assertTrue(f.worker.tasks.isEmpty());
    }
  }

  @Test
  public void aliveUnknownAndMalformedRemainBoundedAndRetainExactHandle() throws Exception {
    try (Fixture f = new Fixture()) {
      f.beginUnknown();
      Object original = get(f.manager, "pendingCleanup");
      f.answer("ALIVE");
      f.worker.next();
      f.answer("UNKNOWN");
      f.worker.next();
      f.answers.add(
          "DSHA_VSCREEN_EXIT_PROOF 1 999:456:2000:"
              + BOOT
              + ":aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee EXITED\n[EXIT=0]");
      f.worker.next();
      assertSame(original, get(f.manager, "pendingCleanup"));
      assertEquals(VirtualScreenLifecycleState.CLOSE_UNKNOWN, f.manager.lifecycleState());
      assertEquals("CLOSE_UNKNOWN", f.journal().getString("state"));
      assertTrue(f.journal().getString("handle").contains(ID.encode()));
      assertEquals(List.of(250L, 1000L, 3000L), f.worker.delays);
      assertEquals(4, f.commands.size());
      assertTrue(f.worker.tasks.isEmpty());
    }
  }

  @Test
  public void unavailableOriginalChannelDoesNotFallbackOrClear() throws Exception {
    try (Fixture f = new Fixture()) {
      f.beginUnknown();
      f.available = false;
      for (int i = 0; i < 3; i++) f.worker.next();
      assertEquals(1, f.commands.size());
      assertNotNull(get(f.manager, "pendingCleanup"));
      assertEquals(VirtualScreenLifecycleState.CLOSE_UNKNOWN, f.manager.lifecycleState());
    }
  }

  @Test
  public void lateExitedAfterEpochChangeCannotClearOrPersistStopped() throws Exception {
    try (Fixture f = new Fixture()) {
      f.beginUnknown();
      Object original = get(f.manager, "pendingCleanup");
      f.duringProbe =
          () -> {
            try {
              ((java.util.concurrent.atomic.AtomicLong) get(f.manager, "LIFECYCLE_EPOCH"))
                  .incrementAndGet();
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          };
      f.answer("EXITED");
      f.worker.next();
      assertSame(original, get(f.manager, "pendingCleanup"));
      assertEquals("CLOSE_UNKNOWN", f.journal().getString("state"));
      assertTrue(f.worker.tasks.isEmpty());
    }
  }

  @Test
  public void changedPendingOwnerAtSameEpochCannotBeClearedByOldProof() throws Exception {
    try (Fixture f = new Fixture()) {
      f.beginUnknown();
      Object old = get(f.manager, "pendingCleanup");
      var constructor = old.getClass().getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      Object replacement =
          constructor.newInstance(
              new VirtualScreenHandshake(20000, "b".repeat(48), ID), "shizuku", f.manager.epoch());
      f.duringProbe =
          () -> {
            try {
              set(f.manager, "pendingCleanup", replacement);
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          };
      f.answer("EXITED");
      f.worker.next();
      assertSame(replacement, get(f.manager, "pendingCleanup"));
      assertEquals("CLOSE_UNKNOWN", f.journal().getString("state"));
      assertTrue(f.worker.tasks.isEmpty());
    }
  }

  @Test
  public void journalFailureRetainsBarrierEvenWithExactExitedProof() throws Exception {
    try (Fixture f = new Fixture()) {
      f.beginUnknown();
      File pending = new File(f.root, "vscreen-session-v1.json.pending");
      assertTrue(pending.mkdir());
      f.answer("EXITED");
      f.worker.next();
      assertNotNull(get(f.manager, "pendingCleanup"));
      assertEquals(VirtualScreenLifecycleState.CLOSE_UNKNOWN, f.manager.lifecycleState());
      assertEquals("CLOSE_UNKNOWN", f.journal().getString("state"));
      assertEquals(1, f.worker.tasks.size());
      assertTrue(pending.delete());
      f.answer("EXITED");
      f.worker.next();
      assertEquals("STOPPED", f.journal().getString("state"));
    }
  }

  @Test
  public void repeatNormalCloseRebindsFenceAndCancelsOldScheduledOwner() throws Exception {
    try (Fixture f = new Fixture()) {
      f.beginUnknown();
      Object old = get(f.manager, "pendingCleanup");
      f.answer("UNKNOWN");
      assertFalse(f.manager.close().optBoolean("ok"));
      Object replacement = get(f.manager, "pendingCleanup");
      assertNotSame(old, replacement);
      assertEquals(2, f.commands.size());
      f.worker.next(); // cancelled old timer: no remote operation
      assertEquals(2, f.commands.size());
      f.answer("EXITED");
      f.worker.next();
      assertNull(get(f.manager, "pendingCleanup"));
      assertEquals("STOPPED", f.journal().getString("state"));
    }
  }

  @Test
  public void queuedProbeAfterEpochChangeDoesNotCallAnyChannel() throws Exception {
    try (Fixture f = new Fixture()) {
      f.beginUnknown();
      ((java.util.concurrent.atomic.AtomicLong) get(f.manager, "LIFECYCLE_EPOCH"))
          .incrementAndGet();
      f.worker.next();
      assertEquals(1, f.commands.size());
      assertNotNull(get(f.manager, "pendingCleanup"));
      assertEquals("CLOSE_UNKNOWN", f.journal().getString("state"));
    }
  }

  @Test
  public void initialExactExitWithStorageFailureKeepsHandleAndReportsReason() throws Exception {
    try (Fixture f = new Fixture()) {
      File pending = new File(f.root, "vscreen-session-v1.json.pending");
      assertTrue(pending.mkdir());
      f.answer("EXITED");
      JSONObject result = f.manager.close();
      assertFalse(result.optBoolean("ok"));
      assertEquals("EXITED", result.optString("coreExitProof"));
      assertEquals("VSCREEN_JOURNAL_UNAVAILABLE", result.optString("reason"));
      assertTrue(result.optBoolean("cleanupPending"));
      assertNotNull(get(f.manager, "pendingCleanup"));
      assertEquals(VirtualScreenLifecycleState.CLOSE_UNKNOWN, f.manager.lifecycleState());
      assertEquals(1, f.worker.tasks.size());
      assertTrue(pending.delete());
      f.answer("EXITED");
      f.worker.next();
      assertEquals("STOPPED", f.journal().getString("state"));
      assertNull(get(f.manager, "pendingCleanup"));
    }
  }

  @Test
  public void initialLateExitedAfterEpochChangeCannotReportCompleted() throws Exception {
    try (Fixture f = new Fixture()) {
      f.duringProbe =
          () -> {
            try {
              ((java.util.concurrent.atomic.AtomicLong) get(f.manager, "LIFECYCLE_EPOCH"))
                  .incrementAndGet();
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          };
      f.answer("EXITED");
      JSONObject result = f.manager.close();
      assertFalse(result.optBoolean("ok"));
      assertEquals("VSCREEN_CLOSE_SUPERSEDED", result.optString("reason"));
      assertNotNull(get(f.manager, "pendingCleanup"));
      assertNotEquals("STOPPED", f.journal().getString("state"));
      assertTrue(f.worker.tasks.isEmpty());
    }
  }
}
