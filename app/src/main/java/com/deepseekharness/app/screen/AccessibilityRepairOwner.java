package com.deepseekharness.app.screen;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.util.AccessibilityRepairState;
import com.deepseekharness.app.util.AdbResult;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Owns only a process-local accessibility reconnect cycle, never an assistant screen grant. */
public final class AccessibilityRepairOwner {
  private static final AccessibilityRepairState STATE = new AccessibilityRepairState();
  private static final Handler TIMER = new Handler(Looper.getMainLooper());
  private static final ThreadPoolExecutor IO =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(2),
          task -> {
            Thread thread = new Thread(task, "dsha-accessibility-repair");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  private AccessibilityRepairOwner() {}

  public static boolean afterAuthenticatedProbe(
      Context context,
      String deviceInstanceId,
      String authenticatedProbe,
      long repairIntentEpoch,
      BooleanSupplier current) {
    // TCP reachability and a historic successful probe cannot authorize a settings write.
    if (authenticatedProbe == null
        || !AdbResult.shellReady(authenticatedProbe)
        || !current.getAsBoolean()) return false;
    Context app = context.getApplicationContext();
    long observed = SystemClock.elapsedRealtime();
    ComponentName own = new ComponentName(app, DshaAccessibilityService.class);
    var ports =
        new AccessibilityRepairState.Ports() {
          public long now() {
            return SystemClock.elapsedRealtime();
          }

          public boolean current() {
            return current.getAsBoolean() && now() - observed < 5_000;
          }

          public boolean permitted() {
            return app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
          }

          public boolean connected() {
            return DshaAccessibilityService.connected();
          }

          public String readEnabled() {
            return Settings.Secure.getString(
                app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
          }

          public boolean writeEnabled(String value) {
            return Settings.Secure.putString(
                app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
          }

          public boolean ownComponent(String value) {
            return own.equals(ComponentName.unflattenFromString(value));
          }

          public void revokeScreenRequests() {
            HttpShellService.revokeDisconnectedScreenGrant(app);
          }

          public void diagnostic(long repairEpoch, String stage, Throwable failure) {
            com.deepseekharness.app.core.DiagnosticLog.record(
                app,
                "ACCESSIBILITY_REPAIR_" + stage + "_PLATFORM_FAILURE",
                "repairEpoch=" + repairEpoch + " failure=" + failure.getClass().getName());
          }
        };
    // These OS facts are checked again on the I/O thread and before each dispatch.
    if (ports.connected()) return false;
    var attempt = STATE.begin(deviceInstanceId, ports, repairIntentEpoch);
    if (attempt == null) return false;
    TIMER.postDelayed(() -> STATE.expire(attempt), 5_000);
    submit(
        attempt,
        () -> {
          if (STATE.disable(attempt))
            TIMER.postDelayed(() -> submit(attempt, () -> STATE.restore(attempt)), 120);
        });
    return true; // Accepted, never a claim that the system service has connected.
  }

  private static void submit(AccessibilityRepairState.Attempt attempt, Runnable work) {
    try {
      IO.execute(work);
    } catch (java.util.concurrent.RejectedExecutionException full) {
      STATE.executorUnavailable(attempt);
    }
  }

  public static void cancel(AccessibilityRepairState.CancelCause cause) {
    STATE.cancel(cause);
  }

  public static void deviceStopped(String instanceId) {
    STATE.deviceStopped(instanceId);
  }

  public static void platformDisconnected() {
    STATE.platformDisconnected();
  }

  public static void serviceConnected() {
    STATE.connected();
  }

  public static AccessibilityRepairState.Snapshot snapshot() {
    return STATE.snapshot();
  }

  public static long intentEpoch() {
    return STATE.intentEpoch();
  }

  public static JSONObject status() {
    var s = STATE.snapshot();
    JSONObject result = new JSONObject();
    try {
      result.put("epoch", s.epoch());
      result.put("phase", s.phase());
      result.put("code", s.code());
      result.put("mayRemainDisabled", s.mayRemainDisabled());
      result.put("nextStep", "SYSTEM_ACCESSIBILITY_SETTINGS");
    } catch (org.json.JSONException ignored) {
    }
    return result;
  }
}
