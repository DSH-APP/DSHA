package com.deepseekharness.app.util;

import java.util.function.Predicate;

/** Process-local, one-cycle accessibility repair. No persisted write or request can be replayed. */
public final class AccessibilityRepairState {
  public enum CancelCause {
    SCREEN_AUTHORITY_REVOKED,
    POLICY_CHANGED,
    PAUSE_CHANGED,
    ADB_INTENT_CHANGED,
    DEVICE_SERVICE_STOPPED,
    PLATFORM_DISCONNECTED,
    EXECUTOR_UNAVAILABLE
  }

  public interface Ports {
    long now();

    boolean current();

    boolean permitted();

    boolean connected();

    String readEnabled() throws Exception;

    boolean writeEnabled(String value) throws Exception;

    boolean ownComponent(String value);

    void revokeScreenRequests();

    void diagnostic(long repairEpoch, String stage, Throwable failure);
  }

  public record Snapshot(long epoch, String phase, String code, boolean mayRemainDisabled) {}

  public static final class Attempt {
    public final String deviceInstanceId;
    private final long epoch;
    private final long deadline;
    private final Ports ports;
    private String disabled;
    private String ownEntry;
    private boolean disableClaimed;
    private boolean restoreClaimed;

    private Attempt(String deviceInstanceId, long epoch, long deadline, Ports ports) {
      this.deviceInstanceId = deviceInstanceId;
      this.epoch = epoch;
      this.deadline = deadline;
      this.ports = ports;
    }
  }

  private long epoch;
  private long lastAttempt = Long.MIN_VALUE;
  private Attempt active;
  private boolean uncertainWrite;
  private int writesInFlight;
  private Snapshot snapshot = new Snapshot(0, "IDLE", "NOT_ATTEMPTED", false);

  public synchronized Snapshot snapshot() {
    return snapshot;
  }

  public synchronized long intentEpoch() {
    return epoch;
  }

  /** Only the Android owner may offer a freshly authenticated, live-device proof. */
  public synchronized Attempt begin(String deviceInstanceId, Ports ports) {
    return begin(deviceInstanceId, ports, epoch);
  }

  public synchronized Attempt begin(String deviceInstanceId, Ports ports, long expectedEpoch) {
    long now = ports.now();
    if (epoch != expectedEpoch
        || active != null
        || writesInFlight != 0
        || uncertainWrite
        || (lastAttempt != Long.MIN_VALUE && now - lastAttempt < 30_000)) return null;
    lastAttempt = now;
    Attempt next = new Attempt(deviceInstanceId, ++epoch, now + 5_000, ports);
    active = next;
    snapshot = new Snapshot(epoch, "CHECKING", "CHECKING_LIVE_FACTS", false);
    return next;
  }

  /** Runs outside the UI thread. A claimed write is never retried, even if its result is unknown. */
  public boolean disable(Attempt a) {
    synchronized (this) {
      if (!current(a) || a.disableClaimed) return false;
    }
    try {
      if (!live(a)) return false;
      String source = a.ports.readEnabled();
      Plan plan = Plan.removeOwn(source, a.ports::ownComponent);
      if (plan == null) return stop(a, "UNAVAILABLE", "SERVICE_NOT_ENABLED_OR_AMBIGUOUS");
      a.ports.revokeScreenRequests();
      // An additional read detects changes during preparation, but Settings has no CAS primitive.
      if (!source.equals(a.ports.readEnabled()))
        return stop(a, "SETTINGS_CHANGED", "SETTINGS_CHANGED_BEFORE_DISABLE");
      if (!live(a)) return false;
      synchronized (this) {
        if (!current(a) || a.disableClaimed) return false;
        a.disabled = plan.disabled;
        a.ownEntry = plan.ownEntry;
        a.disableClaimed = true;
        uncertainWrite = true;
        writesInFlight++;
        snapshot = new Snapshot(epoch, "DISABLE_SENT", "WRITE_MAY_HAVE_OCCURRED", true);
      }
      // Cancellation after the claim cannot recall an in-flight Binder transaction.
      if (!writeOnce(a, a.disabled)) return stop(a, "UNKNOWN", "DISABLE_RESULT_UNKNOWN");
      synchronized (this) {
        if (!current(a)) return false;
        snapshot = new Snapshot(epoch, "DISABLED_WAIT", "WAITING_BOUNDED_RESTORE", true);
        return true;
      }
    } catch (Exception failure) {
      return failed(a, "DISABLE", failure);
    }
  }

  public void restore(Attempt a) {
    synchronized (this) {
      if (!current(a) || !a.disableClaimed || a.restoreClaimed) return;
    }
    try {
      if (!live(a)) return;
      // Never restore the original list: only re-add our entry to the exact D that we wrote.
      if (!a.disabled.equals(a.ports.readEnabled())) {
        stop(a, "SETTINGS_CHANGED", "DISABLED_LIST_NO_LONGER_OWNED");
        return;
      }
      if (!live(a)) return;
      synchronized (this) {
        if (!current(a) || !a.disableClaimed || a.restoreClaimed) return;
        a.restoreClaimed = true;
        writesInFlight++;
        snapshot = new Snapshot(epoch, "RESTORE_SENT", "WRITE_MAY_HAVE_OCCURRED", true);
      }
      String restored = a.disabled.isEmpty() ? a.ownEntry : a.disabled + ":" + a.ownEntry;
      if (!writeOnce(a, restored)) {
        stop(a, "UNKNOWN", "RESTORE_RESULT_UNKNOWN");
        return;
      }
      synchronized (this) {
        if (current(a))
          snapshot =
              new Snapshot(epoch, "WAITING_CONNECTED", "AWAITING_REAL_SERVICE_CALLBACK", true);
      }
    } catch (Exception failure) {
      failed(a, "RESTORE", failure);
    }
  }

  private boolean writeOnce(Attempt a, String value) throws Exception {
    try {
      return a.ports.writeEnabled(value);
    } finally {
      synchronized (this) {
        writesInFlight--;
        // Returning retires the I/O fence, never the separate unknown-result fence.
      }
    }
  }

  private boolean live(Attempt a) {
    synchronized (this) {
      if (!current(a)) return false;
      if (a.ports.now() >= a.deadline) return stop(a, "TIMED_OUT", "REPAIR_DEADLINE");
    }
    if (!a.ports.current()) return stop(a, "CANCELLED", "DEVICE_OR_INTENT_CHANGED");
    if (!a.ports.permitted()) return stop(a, "UNAVAILABLE", "WRITE_SECURE_SETTINGS_MISSING");
    if (a.ports.connected()) return stop(a, "CANCELLED", "ALREADY_CONNECTED");
    synchronized (this) {
      return current(a);
    }
  }

  private synchronized boolean current(Attempt a) {
    return a != null && active == a && epoch == a.epoch;
  }

  private boolean failed(Attempt a, String stage, Throwable failure) {
    synchronized (this) {
      stop(a, a.disableClaimed ? "UNKNOWN" : "UNAVAILABLE", stage + "_PLATFORM_FAILURE");
    }
    // A diagnostic sink cannot change the domain result or hold the cancellation lock.
    try {
      a.ports.diagnostic(a.epoch, stage, failure);
    } catch (Throwable diagnosticUnavailable) {
      // The original failure and state take precedence over optional diagnostic storage.
    }
    return false;
  }

  private synchronized boolean stop(Attempt a, String phase, String code) {
    if (!current(a)) return false;
    active = null;
    snapshot = new Snapshot(++epoch, phase, code, a.disableClaimed);
    return false;
  }

  public synchronized void expire(Attempt a) {
    if (current(a) && a.ports.now() >= a.deadline) stop(a, "TIMED_OUT", "REPAIR_DEADLINE");
  }

  public synchronized void executorUnavailable(Attempt a) {
    if (current(a)) cancel(CancelCause.EXECUTOR_UNAVAILABLE);
  }

  public synchronized void cancel(CancelCause cause) {
    Attempt old = active;
    active = null;
    snapshot =
        new Snapshot(
            ++epoch, "CANCELLED", cause.name(), old != null ? old.disableClaimed : uncertainWrite);
  }

  public synchronized void deviceStopped(String deviceInstanceId) {
    if (active != null && active.deviceInstanceId.equals(deviceInstanceId))
      cancel(CancelCause.DEVICE_SERVICE_STOPPED);
  }

  /** A platform disconnect has no user-origin identity. Own issued disable is handled separately. */
  public synchronized void platformDisconnected() {
    if (active != null && !active.disableClaimed) cancel(CancelCause.PLATFORM_DISCONNECTED);
  }

  /** Called exclusively by the real AccessibilityService.onServiceConnected callback. */
  public synchronized void connected() {
    active = null;
    // A callback cannot retire a still-running old write, which might complete afterwards.
    uncertainWrite = writesInFlight != 0;
    snapshot =
        new Snapshot(
            ++epoch,
            "CONNECTED",
            uncertainWrite ? "REAL_SERVICE_CONNECTED_WRITE_PENDING" : "REAL_SERVICE_CONNECTED",
            uncertainWrite);
  }

  static final class Plan {
    final String disabled;
    final String ownEntry;

    private Plan(String disabled, String ownEntry) {
      this.disabled = disabled;
      this.ownEntry = ownEntry;
    }

    static Plan removeOwn(String source, Predicate<String> own) {
      if (source == null || source.isEmpty()) return null;
      String[] entries = source.split(":", -1);
      String found = null;
      StringBuilder disabled = new StringBuilder();
      boolean appended = false;
      for (String entry : entries) {
        if (entry.trim().isEmpty()) return null;
        if (own.test(entry.trim())) {
          if (found != null) return null;
          found = entry;
        } else {
          if (appended) disabled.append(':');
          disabled.append(entry);
          appended = true;
        }
      }
      return found == null ? null : new Plan(disabled.toString(), found);
    }
  }
}
