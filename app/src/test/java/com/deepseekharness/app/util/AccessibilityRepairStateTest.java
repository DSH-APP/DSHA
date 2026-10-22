package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Development-only OS facts; the production state, cancellation and dispatch code is real. */
public class AccessibilityRepairStateTest {
  private static final String OWN = "app/service";

  private static final class Facts implements AccessibilityRepairState.Ports {
    long time;
    boolean current = true;
    boolean permitted = true;
    boolean connected;
    boolean writeResult = true;
    boolean throwWrite;
    boolean diagnosticThrows;
    Throwable diagnosticFailure;
    String diagnosticStage;
    Exception writeFailure =
        new Exception("unknown Binder outcome", new IllegalStateException("origin"));
    String value = "first/service:" + OWN + ":last/service";
    String nextRead;
    int revoked;
    List<String> writes = new ArrayList<>();
    CountDownLatch entered;
    CountDownLatch release;

    public long now() {
      return time;
    }

    public boolean current() {
      return current;
    }

    public boolean permitted() {
      return permitted;
    }

    public boolean connected() {
      return connected;
    }

    public String readEnabled() {
      String result = value;
      if (nextRead != null) {
        value = nextRead;
        nextRead = null;
      }
      return result;
    }

    public boolean ownComponent(String v) {
      return OWN.equals(v);
    }

    public void revokeScreenRequests() {
      revoked++;
    }

    public void diagnostic(long repairEpoch, String stage, Throwable failure) {
      diagnosticFailure = failure;
      diagnosticStage = stage;
      if (diagnosticThrows) throw new IllegalStateException("diagnostic storage unavailable");
    }

    public boolean writeEnabled(String v) throws Exception {
      writes.add(v);
      if (entered != null) entered.countDown();
      if (release != null && !release.await(2, TimeUnit.SECONDS))
        throw new AssertionError("blocked test");
      value = v;
      if (throwWrite) throw writeFailure;
      return writeResult;
    }
  }

  @Test
  public void boundedCycleAddsOnlyOwnEntryAndWaitsForRealConnection() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("device", f);
    assertTrue(s.disable(a));
    assertFalse(s.disable(a));
    assertEquals(1, f.revoked);
    assertEquals("first/service:last/service", f.value);
    s.restore(a);
    s.restore(a);
    assertEquals(
        List.of("first/service:last/service", "first/service:last/service:" + OWN), f.writes);
    assertEquals("WAITING_CONNECTED", s.snapshot().phase());
    assertNull(s.begin("device", f));
    s.connected();
    assertEquals("CONNECTED", s.snapshot().phase());
    assertFalse(s.snapshot().mayRemainDisabled());
  }

  @Test
  public void noPermissionIsExplicitlyUnavailable() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    f.permitted = false;
    assertFalse(s.disable(s.begin("d", f)));
    assertEquals("WRITE_SECURE_SETTINGS_MISSING", s.snapshot().code());
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void notEnabledAndAmbiguousListsNeverWrite() {
    for (String value : new String[] {"", "other/service", OWN + ":" + OWN, OWN + ":"}) {
      var s = new AccessibilityRepairState();
      var f = new Facts();
      f.value = value;
      assertFalse(s.disable(s.begin("d", f)));
      assertTrue(f.writes.isEmpty());
    }
  }

  @Test
  public void staleDeviceOrAdbIntentNeverWrites() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    f.current = false;
    assertFalse(s.disable(a));
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void everyExplicitCancelCauseFencesQueuedWork() {
    for (var cause : AccessibilityRepairState.CancelCause.values()) {
      var s = new AccessibilityRepairState();
      var f = new Facts();
      var a = s.begin("d", f);
      s.cancel(cause);
      assertFalse(s.disable(a));
      s.restore(a);
      assertTrue(f.writes.isEmpty());
      assertEquals(cause.name(), s.snapshot().code());
    }
  }

  @Test
  public void cancelAfterDisableNeverSecretlyReenables() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    assertTrue(s.disable(a));
    s.cancel(AccessibilityRepairState.CancelCause.SCREEN_AUTHORITY_REVOKED);
    s.restore(a);
    assertEquals(1, f.writes.size());
    assertTrue(s.snapshot().mayRemainDisabled());
    f.time = 60_000;
    assertNull(s.begin("d", f));
  }

  @Test
  public void unrelatedSettingsChangeStopsRestoreInsteadOfRestoringOriginalList() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    assertTrue(s.disable(a));
    f.value = "new/service";
    s.restore(a);
    assertEquals(1, f.writes.size());
    assertEquals("new/service", f.value);
    assertEquals("DISABLED_LIST_NO_LONGER_OWNED", s.snapshot().code());
  }

  @Test
  public void settingsChangeDuringPreparationStopsDisable() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    f.nextRead = "new/service";
    assertFalse(s.disable(s.begin("d", f)));
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void ownPlannedDisconnectRevokesAuthorityButDoesNotCancelRestore() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    assertTrue(s.disable(a));
    s.platformDisconnected();
    s.restore(a);
    assertEquals(2, f.writes.size());
    assertEquals("WAITING_CONNECTED", s.snapshot().phase());
    assertEquals(1, f.revoked);
  }

  @Test
  public void disconnectBeforeAnyIssuedDisableCancelsAttempt() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    s.platformDisconnected();
    assertFalse(s.disable(a));
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void unknownDisableResultNeverRetries() {
    for (boolean throwsFailure : new boolean[] {false, true}) {
      var s = new AccessibilityRepairState();
      var f = new Facts();
      f.writeResult = false;
      f.throwWrite = throwsFailure;
      var a = s.begin("d", f);
      assertFalse(s.disable(a));
      s.restore(a);
      assertEquals("UNKNOWN", s.snapshot().phase());
      f.time = 60_000;
      assertNull(s.begin("d", f));
      assertEquals(1, f.writes.size());
    }
  }

  @Test
  public void unknownRestoreNeverReplays() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    assertTrue(s.disable(a));
    f.writeResult = false;
    s.restore(a);
    s.restore(a);
    assertEquals(2, f.writes.size());
    assertEquals("UNKNOWN", s.snapshot().phase());
  }

  @Test
  public void deadlineBeforeWriteHasNoSideEffect() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    f.time = 5_000;
    s.expire(a);
    assertFalse(s.disable(a));
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void revokeAndDeadlineDoNotWaitForBlockedPlatformWrite() throws Exception {
    for (boolean deadline : new boolean[] {false, true}) {
      var s = new AccessibilityRepairState();
      var f = new Facts();
      f.entered = new CountDownLatch(1);
      f.release = new CountDownLatch(1);
      var a = s.begin("d", f);
      Thread worker = new Thread(() -> s.disable(a));
      worker.start();
      assertTrue(f.entered.await(1, TimeUnit.SECONDS));
      if (deadline) {
        f.time = 5_000;
        s.expire(a);
      } else s.cancel(AccessibilityRepairState.CancelCause.POLICY_CHANGED);
      assertEquals(deadline ? "TIMED_OUT" : "CANCELLED", s.snapshot().phase());
      assertTrue(s.snapshot().mayRemainDisabled());
      // Even a real new connection cannot retire the still-blocked old platform call.
      s.connected();
      f.time = 60_000;
      assertNull(s.begin("replacement", f));
      f.release.countDown();
      worker.join(1_000);
      assertFalse(worker.isAlive());
      s.restore(a);
      assertEquals(1, f.writes.size());
      // A callback preceding the write return cannot clear its unknown-result fence.
      assertNull(s.begin("replacement", f));
      s.connected();
      assertNotNull(s.begin("replacement", f));
    }
  }

  @Test
  public void oldDeviceStopCannotCancelReplacementInstance() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("new", f);
    s.deviceStopped("old");
    assertTrue(s.disable(a));
    s.deviceStopped("new");
    s.restore(a);
    assertEquals(1, f.writes.size());
  }

  @Test
  public void actualConnectionInvalidatesLateWritesWithoutRestoringGrants() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    assertTrue(s.disable(a));
    s.connected();
    s.restore(a);
    assertEquals(1, f.writes.size());
    assertEquals(1, f.revoked);
  }

  @Test
  public void revocationBeforeDelayedAuthenticatedOfferRejectsOldRepairEpoch() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    long probeEpoch = s.intentEpoch();
    s.cancel(AccessibilityRepairState.CancelCause.POLICY_CHANGED);
    assertNull(s.begin("d", f, probeEpoch));
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void changedDeviceOrPermissionAfterDisablePreventsRestore() {
    for (boolean revokePermission : new boolean[] {false, true}) {
      var s = new AccessibilityRepairState();
      var f = new Facts();
      var a = s.begin("d", f);
      assertTrue(s.disable(a));
      if (revokePermission) f.permitted = false;
      else f.current = false;
      s.restore(a);
      assertEquals(1, f.writes.size());
      assertTrue(s.snapshot().mayRemainDisabled());
    }
  }

  @Test
  public void connectedFactAloneDoesNotDeclareCallbackSuccess() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    f.connected = true;
    assertFalse(s.disable(s.begin("d", f)));
    assertNotEquals("CONNECTED", s.snapshot().phase());
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void platformDiagnosticReceivesOriginalThrowableAndCause() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    f.throwWrite = true;
    assertFalse(s.disable(s.begin("d", f)));
    assertSame(f.writeFailure, f.diagnosticFailure);
    assertSame(f.writeFailure.getCause(), f.diagnosticFailure.getCause());
    assertEquals("DISABLE", f.diagnosticStage);
    assertEquals("UNKNOWN", s.snapshot().phase());
  }

  @Test
  public void failingDiagnosticSinkCannotMaskRestoreFailureOrTriggerRetry() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    assertTrue(s.disable(a));
    f.throwWrite = true;
    f.diagnosticThrows = true;
    s.restore(a);
    s.restore(a);
    assertSame(f.writeFailure, f.diagnosticFailure);
    assertEquals("RESTORE", f.diagnosticStage);
    assertEquals("UNKNOWN", s.snapshot().phase());
    assertEquals("RESTORE_PLATFORM_FAILURE", s.snapshot().code());
    assertEquals(2, f.writes.size());
    assertTrue(s.snapshot().mayRemainDisabled());
  }

  @Test
  public void freshProcessCannotReplayAnOldAttempt() {
    var old = new AccessibilityRepairState();
    var f = new Facts();
    var a = old.begin("d", f);
    assertTrue(old.disable(a));
    var fresh = new AccessibilityRepairState();
    fresh.restore(a);
    assertEquals(1, f.writes.size());
    assertEquals("IDLE", fresh.snapshot().phase());
    assertFalse(fresh.disable(fresh.begin("d", f)));
    assertEquals(1, f.writes.size());
  }

  @Test
  public void ownOnlyListCanBeRestoredWithoutOtherSettingsWrites() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    f.value = OWN;
    var a = s.begin("d", f);
    assertTrue(s.disable(a));
    assertEquals("", f.value);
    s.restore(a);
    assertEquals(OWN, f.value);
    assertEquals(2, f.writes.size());
  }
}
