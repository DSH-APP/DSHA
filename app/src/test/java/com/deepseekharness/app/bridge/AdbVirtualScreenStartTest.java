package com.deepseekharness.app.bridge;

import static org.junit.Assert.*;
import com.deepseekharness.app.capabilities.CapabilityResult;
import com.deepseekharness.app.device.DeviceTransportResult;
import com.deepseekharness.app.util.AdbEnvironmentTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class AdbVirtualScreenStartTest {
  private static final String TICKET = "0123456789abcdef0123456789abcdef0123456789abcdef";

  private static void notSent(DeviceTransportResult value, String code) {
    assertEquals("adb", value.transport);
    assertEquals(CapabilityResult.State.NOT_SENT, value.outcome.state);
    assertEquals(code, value.outcome.code);
    assertTrue(value.outputComplete);
    assertTrue(value.localExitConfirmed);
    assertFalse(value.remoteExitConfirmed);
  }

  private static void unknown(DeviceTransportResult value) {
    assertEquals(CapabilityResult.State.UNKNOWN, value.outcome.state);
    assertFalse(value.outputComplete);
    assertFalse(value.localExitConfirmed);
    assertFalse(value.remoteExitConfirmed);
  }

  @Test
  public void invalidTicketNeverEntersAdmissionOrHelper() {
    var admitted = new AtomicInteger();
    var result =
        AdbVirtualScreenStart.execute(
            "invalid",
            () -> false,
            () -> "cancelled",
            boundary -> {
              admitted.incrementAndGet();
              throw new AssertionError("Must not enter native admission");
            });
    notSent(result, "VSCREEN_ADB_INVALID_TICKET");
    assertEquals(0, admitted.get());
    // The real public adapter must refuse before resolving the Context or creating any helper.
    var nativeResult = AdbBridge.executeVirtualScreenTyped(null, "unused", "invalid");
    notSent(nativeResult, "VSCREEN_ADB_INVALID_TICKET");
    assertEquals(result.output, nativeResult.output);
    assertEquals(nativeResult.output, AdbBridge.executeVirtualScreen(null, "unused", "invalid"));
  }

  @Test
  public void cancellationBeforeAdmissionAndAtTheHelperBoundaryIsNotSent() {
    for (boolean initial : new boolean[] {true, false}) {
      var cancelled = new AtomicBoolean(initial);
      var helper = new AtomicInteger();
      var result =
          AdbVirtualScreenStart.execute(
              TICKET,
              cancelled::get,
              () -> "cancelled",
              boundary -> {
                cancelled.set(true);
                if (!boundary.enter()) return "cancelled";
                helper.incrementAndGet();
                return "sent";
              });
      notSent(result, "VSCREEN_ADB_CANCELLED_BEFORE_START");
      assertEquals(0, helper.get());
    }
  }

  @Test
  public void environmentRefusalBeforeHelperIsNotSent() {
    var result =
        AdbVirtualScreenStart.execute(
            TICKET,
            () -> false,
            () -> "cancelled",
            boundary -> {
              throw new AdbEnvironmentTask.Busy("controlled pre-start refusal");
            });
    notSent(result, "VSCREEN_ADB_ENVIRONMENT_BUSY");
  }

  @Test
  public void helperTextOrMissingBoundaryCannotProveNotSent() {
    var helper = new AtomicInteger();
    var result =
        AdbVirtualScreenStart.execute(
            TICKET,
            () -> false,
            () -> "cancelled",
            boundary -> {
              assertTrue(boundary.enter());
              assertFalse(boundary.enter());
              helper.incrementAndGet();
              return "NOT_SENT\n[POLICY_BLOCKED] denied\n[EXIT=126]";
            });
    unknown(result);
    assertEquals(1, helper.get());
    unknown(
        AdbVirtualScreenStart.execute(
            TICKET, () -> false, () -> "cancelled", boundary -> "ticket gone; no commit evidence"));
  }

  @Test
  public void refusalAfterEnteringHelperAndCancellationCannotBecomeNotSent() {
    for (boolean interrupted : new boolean[] {false, true}) {
      try {
        var result =
            AdbVirtualScreenStart.execute(
                TICKET,
                () -> false,
                () -> "cancelled",
                boundary -> {
                  assertTrue(boundary.enter());
                  if (interrupted)
                    throw new InterruptedException("controlled post-entry cancellation");
                  throw new AdbEnvironmentTask.Busy("controlled post-entry failure");
                });
        unknown(result);
        if (interrupted) assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  private static com.deepseekharness.app.util.OneShotLaunchAuthority permit() {
    var authority = new com.deepseekharness.app.util.OneShotLaunchAuthority();
    assertTrue(authority.issue(TICKET, "managed-command", 4, 100, 1000));
    assertTrue(authority.authorize(TICKET, "managed-command", 4, 101));
    return authority;
  }

  @Test
  public void closedHelperAndNativeWithdrawalProveConnectFailureWasNotSent() {
    var authority = permit();
    var helpers = new AtomicInteger();
    var result =
        AdbVirtualScreenStart.executeTyped(
            TICKET,
            () -> false,
            () -> "cancelled",
            boundary -> {
              assertTrue(boundary.enter());
              helpers.incrementAndGet();
              return new AdbVirtualScreenStart.HelperResult(
                  "CONNECT_FAIL: unavailable\n[EXIT=124]", true, true);
            },
            () -> authority.withdrawUncommitted(TICKET, "managed-command", 4, 102));
    notSent(result, "VSCREEN_ADB_NOT_COMMITTED");
    assertEquals(1, helpers.get());
    assertFalse(authority.commit(TICKET, 4, 103));
  }

  @Test
  public void lostCommitReplyOrForgedFailureTextAfterCommitRemainsUnknown() {
    for (String output :
        new String[] {
          "EXECUTION_UNKNOWN: ADB_COMMIT_BRIDGE_TIMEOUT\n[EXIT=125]",
          "CONNECT_FAIL: NOT_SENT\n[EXIT=124]"
        }) {
      var authority = permit();
      var result =
          AdbVirtualScreenStart.executeTyped(
              TICKET,
              () -> false,
              () -> "cancelled",
              boundary -> {
                assertTrue(boundary.enter());
                assertTrue(authority.commit(TICKET, 4, 102));
                return new AdbVirtualScreenStart.HelperResult(output, true, true);
              },
              () -> authority.withdrawUncommitted(TICKET, "managed-command", 4, 103));
      unknown(result);
    }
  }

  @Test
  public void incompleteLiveOrUnownedHelperNeverAttemptsWithdrawal() {
    for (int scenario = 0; scenario < 3; scenario++) {
      final int state = scenario;
      var withdrawals = new AtomicInteger();
      var result =
          AdbVirtualScreenStart.executeTyped(
              TICKET,
              () -> false,
              () -> "cancelled",
              boundary -> {
                if (state != 2) assertTrue(boundary.enter());
                return new AdbVirtualScreenStart.HelperResult(
                    "CONNECT_FAIL: text cannot prove state", state != 0, state != 1);
              },
              () -> {
                withdrawals.incrementAndGet();
                return true;
              });
      unknown(result);
      assertEquals(0, withdrawals.get());
    }
  }

  @Test
  public void missingNativePermitCannotUpgradeAClosedHelper() {
    var result =
        AdbVirtualScreenStart.executeTyped(
            TICKET,
            () -> false,
            () -> "cancelled",
            boundary -> {
              assertTrue(boundary.enter());
              return new AdbVirtualScreenStart.HelperResult(
                  "CONNECT_FAIL: no current permit", true, true);
            },
            () -> false);
    unknown(result);
  }
}
