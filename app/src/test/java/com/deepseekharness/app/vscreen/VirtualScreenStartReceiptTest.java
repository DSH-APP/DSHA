package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import com.deepseekharness.app.capabilities.CapabilityResult;
import com.deepseekharness.app.device.DeviceTransportIdentity;
import com.deepseekharness.app.device.DeviceTransportResult;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import com.deepseekharness.app.util.VirtualScreenLifecycleState;
import java.io.File;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Actual Manager/journal with controlled native receipts; no privileged process is started. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenStartReceiptTest {
  @org.junit.Before
  public void standardVirtualScreenFeatureOnly() {
    org.junit.Assume.assumeFalse(com.deepseekharness.app.BuildConfig.LOW_ANDROID);
  }

  private static final String BOOT = "11111111-2222-3333-4444-555555555555";
  private static final String WORDS = "[DEVICE_CHANNEL_UNAVAILABLE] NOT_SENT\n[EXIT=124]";
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static Object get(VirtualScreenManager manager, String name) throws Exception {
    var f = VirtualScreenManager.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(manager);
  }

  private static void set(VirtualScreenManager manager, String name, Object value)
      throws Exception {
    var f = VirtualScreenManager.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(manager, value);
  }

  private final class Fixture implements AutoCloseable {
    final File root = temporary.newFolder();
    final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    final Context context =
        new ContextWrapper(RuntimeEnvironment.getApplication()) {
          @Override
          public File getFilesDir() {
            return root;
          }

          @Override
          public ApplicationInfo getApplicationInfo() {
            var info = new ApplicationInfo();
            info.sourceDir = new File(root, "base.apk").getAbsolutePath();
            return info;
          }
        };
    int calls, legacyCalls;
    String transport = "shizuku", actualCommand = "";
    boolean legacy;
    Runnable duringStart;
    DeviceTransportResult receipt =
        DeviceTransportResult.notSent("shizuku", "SHIZUKU_SERVICE_NOT_READY", WORDS);
    final VirtualScreenChannel channel =
        new VirtualScreenChannel() {
          public String id() {
            return transport;
          }

          public boolean available(Context ignored) {
            return true;
          }

          public String start(Context ignored, String command) {
            legacyCalls++;
            return WORDS;
          }

          public DeviceTransportResult startTyped(Context ignored, String command) {
            calls++;
            actualCommand = command;
            if (duringStart != null) duringStart.run();
            return legacy ? VirtualScreenChannel.super.startTyped(ignored, command) : receipt;
          }
        };
    final VirtualScreenManager manager =
        new VirtualScreenManager(
            context,
            () -> 100,
            new VirtualScreenManager.Visuals() {
              public void present() {}

              public void stopped(long epoch) {}
            },
            new VirtualScreenManager.Channels() {
              public VirtualScreenChannel choose(Context ignored) {
                return channel;
              }

              public VirtualScreenChannel find(String id) {
                throw new AssertionError("No probe/fallback is expected");
              }
            },
            () -> BOOT,
            worker);

    Fixture() throws Exception {}

    JSONObject journal() throws Exception {
      return new VirtualScreenSessionJournal(root).load();
    }

    public void close() throws Exception {
      worker.shutdown();
      assertTrue(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  @Test
  public void nativeNotSentDurablyStopsAndOnlyAnotherExplicitCreateTriesAgain() throws Exception {
    try (Fixture f = new Fixture()) {
      JSONObject result = f.manager.start("portrait");
      assertEquals("DEVICE_CHANNEL_UNAVAILABLE", result.getString("error"));
      assertEquals("SHIZUKU_SERVICE_NOT_READY", result.getString("reason"));
      assertEquals("NOT_SENT", result.getString("state"));
      assertEquals(VirtualScreenLifecycleState.STOPPED, f.manager.lifecycleState());
      assertNull(get(f.manager, "core"));
      assertNull(get(f.manager, "pendingCleanup"));
      assertEquals("STOPPED", f.journal().getString("state"));
      assertEquals("SHIZUKU_SERVICE_NOT_READY", f.journal().getString("lastStartReason"));
      assertEquals("", f.journal().getString("handle"));
      assertEquals(1, f.calls);
      assertEquals(0, f.legacyCalls);
      assertEquals(
          "SHIZUKU_SERVICE_NOT_READY",
          VirtualScreenManager.safeStartOutcome(result).getString("reason"));
      f.receipt =
          DeviceTransportResult.notSent(
              "shizuku", "SYNTHETIC_UPPERCASE_BODY", "SYNTHETIC_UPPERCASE_BODY");
      JSONObject next = f.manager.start("portrait");
      assertEquals(2, f.calls);
      assertEquals("VSCREEN_START_NOT_SENT", next.getString("reason"));
      assertFalse(
          VirtualScreenManager.safeStartOutcome(next)
              .toString()
              .contains("SYNTHETIC_UPPERCASE_BODY"));
      assertEquals("VSCREEN_START_NOT_SENT", f.journal().getString("lastStartReason"));
    }
  }

  @Test
  public void closedNewAdbPermitPersistsNotSentWithoutRetrying() throws Exception {
    try (Fixture f = new Fixture()) {
      f.transport = "adb";
      f.duringStart =
          () -> {
            String ticket = f.manager.adbLaunchTicketFor(f.actualCommand);
            assertFalse(ticket.isEmpty());
            assertTrue(f.manager.authorizeAdbLaunchPlan(ticket, f.actualCommand));
            assertTrue(f.manager.withdrawUncommittedAdbLaunch(ticket, f.actualCommand));
            assertFalse(f.manager.commitAdbLaunch(ticket));
            f.receipt =
                DeviceTransportResult.notSent(
                    "adb", "VSCREEN_ADB_NOT_COMMITTED", "CONNECT_FAIL: unavailable\n[EXIT=124]");
          };
      JSONObject result = f.manager.start("portrait");
      assertEquals("DEVICE_CHANNEL_UNAVAILABLE", result.getString("error"));
      assertEquals("VSCREEN_ADB_NOT_COMMITTED", result.getString("reason"));
      assertEquals("NOT_SENT", result.getString("state"));
      assertEquals(VirtualScreenLifecycleState.STOPPED, f.manager.lifecycleState());
      assertFalse(result.optBoolean("cleanupPending"));
      assertEquals("STOPPED", f.journal().getString("state"));
      assertEquals("VSCREEN_ADB_NOT_COMMITTED", f.journal().getString("lastStartReason"));
      assertEquals(1, f.calls);
      assertEquals(
          "VSCREEN_ADB_NOT_COMMITTED",
          VirtualScreenManager.safeStartOutcome(result).getString("reason"));
    }
  }

  @Test
  public void sameErrorWordsFromLegacyOrTypedUnknownNeverProveNoDispatch() throws Exception {
    for (boolean legacy : new boolean[] {true, false})
      try (Fixture f = new Fixture()) {
        f.legacy = legacy;
        f.receipt =
            DeviceTransportResult.unknown(
                "shizuku",
                "SHIZUKU_SERVICE_NOT_READY",
                WORDS,
                true,
                DeviceTransportIdentity.unknown());
        JSONObject result = f.manager.start("portrait");
        assertEquals("VSCREEN_START_UNKNOWN", result.getString("error"));
        assertTrue(result.optBoolean("cleanupPending"));
        assertEquals("START_UNKNOWN", f.journal().getString("state"));
        assertEquals(legacy ? 1 : 0, f.legacyCalls);
        f.manager.start("portrait");
        assertEquals(1, f.calls);
      }
  }

  @Test
  public void incompleteContradictoryOrWrongTransportNotSentReceiptsRemainUnknown()
      throws Exception {
    for (int variant = 0; variant < 5; variant++)
      try (Fixture f = new Fixture()) {
        f.receipt =
            new DeviceTransportResult(
                variant == 2 ? "root" : "shizuku",
                CapabilityResult.notSent("SHIZUKU_SERVICE_NOT_READY", ""),
                WORDS,
                variant == 3 ? 0 : null,
                variant != 0,
                variant != 1,
                variant == 4,
                DeviceTransportIdentity.unknown());
        assertEquals("VSCREEN_START_UNKNOWN", f.manager.start("portrait").getString("error"));
        assertEquals("START_UNKNOWN", f.journal().getString("state"));
        assertEquals(1, f.calls);
      }
  }

  @Test
  public void lateOldNotSentCannotClearNewEpochOrItsSavedHandle() throws Exception {
    for (int variant = 0; variant < 3; variant++)
      try (Fixture f = new Fixture()) {
        var replacement =
            new VirtualScreenHandshake(
                19001,
                "b".repeat(48),
                VirtualScreenCoreIdentity.parse(
                    "124:457:2000:" + BOOT + ":bbbbbbbb-cccc-dddd-eeee-ffffffffffff"));
        int changed = variant;
        f.duringStart =
            () -> {
              try {
                if (changed == 0)
                  ((AtomicLong) get(f.manager, "LIFECYCLE_EPOCH")).incrementAndGet();
                else if (changed == 1) {
                  set(f.manager, "core", replacement);
                  set(f.manager, "lifecycleState", VirtualScreenLifecycleState.READY);
                } else {
                  var type = Class.forName(VirtualScreenManager.class.getName() + "$ClosedSession");
                  var constructor = type.getDeclaredConstructors()[0];
                  constructor.setAccessible(true);
                  set(
                      f.manager,
                      "pendingCleanup",
                      constructor.newInstance(replacement, "shizuku", f.manager.epoch()));
                  set(f.manager, "lifecycleState", VirtualScreenLifecycleState.CLOSE_UNKNOWN);
                }
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            };
        JSONObject result = f.manager.start("portrait");
        assertEquals("VSCREEN_START_CANCELLED", result.getString("error"));
        if (changed == 1) {
          assertSame(replacement, get(f.manager, "core"));
          assertEquals(VirtualScreenLifecycleState.READY, f.manager.lifecycleState());
        }
        if (changed == 2) {
          assertNotNull(get(f.manager, "pendingCleanup"));
          assertEquals(VirtualScreenLifecycleState.CLOSE_UNKNOWN, f.manager.lifecycleState());
        }
        assertNotEquals("STOPPED", f.journal().getString("state"));
        assertEquals(1, f.calls);
      }
  }

  @Test
  public void storageFailureKeepsTheBarrierDespiteNativeNoDispatchProof() throws Exception {
    try (Fixture f = new Fixture()) {
      f.duringStart = () -> assertTrue(new File(f.root, "vscreen-session-v1.json.pending").mkdir());
      JSONObject result = f.manager.start("portrait");
      assertEquals("VSCREEN_JOURNAL_UNAVAILABLE", result.getString("error"));
      assertEquals("NOT_SENT", result.getString("state"));
      assertTrue(result.optBoolean("cleanupPending"));
      assertEquals(VirtualScreenLifecycleState.START_UNKNOWN, f.manager.lifecycleState());
      assertNotEquals("STOPPED", f.journal().getString("state"));
      assertEquals(1, f.calls);
    }
  }
}
