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

/** Real persisted records/new managers; controlled channel proofs are not device exit evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenPersistentCleanupTest {
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

    ManualWorker() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable work, long delay, TimeUnit unit) {
      Task task = new Task(work);
      if (delay == 0) task.run();
      else tasks.add(task);
      return task;
    }

    void next() throws Exception {
      var task = tasks.remove();
      task.run();
      if (!task.isCancelled()) task.get();
    }
  }

  private static Object field(VirtualScreenManager manager, String name) throws Exception {
    var field = VirtualScreenManager.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(manager);
  }

  private final class Fixture implements AutoCloseable {
    final File root = temporary.newFolder();
    final ManualWorker worker = new ManualWorker();
    final List<String> commands = new ArrayList<>();
    final Context context =
        new ContextWrapper(RuntimeEnvironment.getApplication()) {
          @Override
          public File getFilesDir() {
            return root;
          }

          @Override
          public ApplicationInfo getApplicationInfo() {
            var info = new ApplicationInfo();
            info.sourceDir = new File(root, "current-cover-base.apk").getAbsolutePath();
            return info;
          }
        };
    final VirtualScreenSessionJournal journal = new VirtualScreenSessionJournal(root);
    final VirtualScreenHandshake handle;
    VirtualScreenManager manager;
    boolean available;
    int chosen;
    final VirtualScreenChannel channel =
        new VirtualScreenChannel() {
          public String id() {
            return "adb";
          }

          public boolean available(Context ignored) {
            return available;
          }

          public String start(Context ignored, String command) {
            assertTrue(command.contains("--probe-exit --identity"));
            assertTrue(command.contains(ID.encode()));
            assertTrue(command.contains("current-cover-base.apk"));
            assertFalse(command.contains("--launch"));
            String ticket = manager.adbLaunchTicketFor(command);
            assertFalse(ticket.isEmpty());
            assertTrue(manager.authorizeAdbLaunchPlan(ticket, command));
            assertTrue(manager.commitAdbLaunch(ticket));
            assertFalse(manager.commitAdbLaunch(ticket));
            commands.add(command);
            return "DSHA_VSCREEN_EXIT_PROOF 1 " + ID.encode() + " EXITED\n[EXIT=0]";
          }
        };

    Fixture() throws Exception {
      int port;
      try (ServerSocket socket = new ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      handle = new VirtualScreenHandshake(port, "a".repeat(48), ID);
    }

    void restore(String state, String payload) throws Exception {
      journal.save(
          1,
          new JSONObject()
              .put("state", state)
              .put("boot", BOOT)
              .put("channel", "adb")
              .put("handle", payload));
      open();
    }

    void open() {
      manager =
          new VirtualScreenManager(
              context,
              () -> 100,
              new VirtualScreenManager.Visuals() {
                public void present() {}

                public void stopped(long fence) {}
              },
              new VirtualScreenManager.Channels() {
                public VirtualScreenChannel choose(Context ignored) {
                  chosen++;
                  return null;
                }

                public VirtualScreenChannel find(String id) {
                  assertEquals("adb", id);
                  return channel;
                }
              },
              () -> BOOT,
              worker);
    }

    public void close() {
      worker.shutdownNow();
    }
  }

  @Test
  public void restoredExactAdbIdentitySurvivesUnavailableChannelThenCommitsExited()
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.restore("CLOSE_UNKNOWN", f.handle.payload());
      assertEquals(VirtualScreenLifecycleState.DISCONNECTED, f.manager.lifecycleState());
      assertNotNull(field(f.manager, "pendingCleanup"));
      assertNull(field(f.manager, "core"));
      JSONObject waiting = f.manager.close();
      assertFalse(waiting.optBoolean("ok"));
      assertTrue(waiting.optBoolean("cleanupIdentityAvailable"));
      assertEquals(ID.instance, waiting.getString("coreInstance"));
      assertEquals("adb", waiting.getString("cleanupChannel"));
      assertEquals("ORIGINAL_CHANNEL_UNAVAILABLE", waiting.getString("cleanupProbeReason"));
      assertTrue(VirtualScreenActivity.outcomeText(waiting).contains("ADB"));
      assertEquals(0, f.commands.size());
      assertEquals(0, f.chosen);
      assertTrue(f.journal.load().getString("handle").contains(ID.encode()));
      f.available = true;
      f.worker.next();
      assertEquals(1, f.commands.size());
      assertEquals(0, f.chosen);
      assertEquals(VirtualScreenLifecycleState.STOPPED, f.manager.lifecycleState());
      assertNull(field(f.manager, "pendingCleanup"));
      assertEquals("STOPPED", f.journal.load().getString("state"));
      assertEquals("", f.journal.load().getString("handle"));
    }
  }

  @Test
  public void restoredMissingHandshakeCannotInventProbeIdentityOrClearUnknown() throws Exception {
    try (Fixture f = new Fixture()) {
      f.restore("START_UNKNOWN", "");
      f.available = true;
      JSONObject waiting = f.manager.close();
      assertFalse(waiting.optBoolean("ok"));
      assertTrue(waiting.optBoolean("cleanupPending"));
      assertFalse(waiting.optBoolean("cleanupIdentityAvailable"));
      assertEquals("IDENTITY_NOT_CAPTURED", waiting.getString("cleanupProbeReason"));
      assertFalse(waiting.has("coreInstance"));
      assertEquals(VirtualScreenLifecycleState.START_UNKNOWN, f.manager.lifecycleState());
      assertEquals("START_UNKNOWN", f.journal.load().getString("state"));
      assertEquals(0, f.commands.size());
      assertTrue(f.worker.tasks.isEmpty());
      assertEquals(0, f.chosen);
    }
  }

  @Test
  public void freshUnavailableSelectionNeverDispatchesOrCreatesPendingCoreIdentity()
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.open();
      JSONObject result = f.manager.start("portrait");
      assertEquals("DEVICE_CHANNEL_UNAVAILABLE", result.getString("error"));
      assertEquals(1, f.chosen);
      assertEquals(0, f.commands.size());
      assertEquals(VirtualScreenLifecycleState.STOPPED, f.manager.lifecycleState());
      assertNull(field(f.manager, "pendingCleanup"));
      assertNull(field(f.manager, "core"));
      assertFalse(result.has("coreInstance"));
      assertEquals("STOPPED", f.journal.load().getString("state"));
      assertEquals("", f.journal.load().getString("handle"));
      assertTrue(f.worker.tasks.isEmpty());
    }
  }
}
