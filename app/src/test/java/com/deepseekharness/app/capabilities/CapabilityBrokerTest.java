package com.deepseekharness.app.capabilities;

import com.deepseekharness.app.core.RuntimeSnapshot;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CapabilityBrokerTest {
  private final AtomicLong clock = new AtomicLong(100);
  private final CapabilityBroker broker = new CapabilityBroker(clock::get);
  private static final RuntimeSnapshot.Epoch RUN =
      new RuntimeSnapshot.Epoch("install-a", 7, "runtime-a");

  private CapabilityRequest request(String capability) {
    return CapabilityRequest.create(
        capability,
        "tap",
        RUN,
        "install-a",
        "bridge-a",
        new ScreenTargetEvidence(0, 12, "com.example.app"),
        3,
        broker.epoch(capability),
        200);
  }

  @Test
  public void confirmationIsSingleSlotAndSingleDecision() throws Exception {
    var first = broker.beginConfirmation(request("screen"));
    assertNotNull(first);
    assertNull(broker.beginConfirmation(request("screen")));
    assertTrue(broker.resolve(first, true));
    assertFalse(broker.resolve(first, false));
    assertTrue(first.await(0, TimeUnit.MILLISECONDS));
    broker.finish(first);
    assertNotNull(broker.beginConfirmation(request("screen")));
  }

  @Test
  public void revokeWakesOldWaiterWithoutPretendingUserDenied() throws Exception {
    var first = broker.beginConfirmation(request("screen"));
    broker.revoke("screen");
    assertFalse(first.await(0, TimeUnit.MILLISECONDS));
    assertNull(first.answer());
    var replacement = broker.beginConfirmation(request("screen"));
    assertNotNull(replacement);
    assertFalse(broker.resolve(first, true));
    broker.finish(first);
    assertTrue(broker.pending(replacement));
    assertTrue(broker.resolve(replacement, false));
    assertEquals(Boolean.FALSE, replacement.answer());
  }

  @Test
  public void revocationIsScopedButRunStopFencesEverything() {
    var screen = request("screen");
    var sms = request("sms.read");
    broker.revoke("sms.read");
    assertTrue(broker.current(screen));
    assertFalse(broker.current(sms));
    var currentSms = request("sms.read");
    broker.revokeAll();
    assertFalse(broker.current(screen));
    assertFalse(broker.current(currentSms));
    assertTrue(broker.current(request("screen")));
  }

  @Test
  public void dispatchClaimIsOneUseAndNeverWaitsForAdapter() {
    var request = request("screen");
    assertNull(broker.claimDispatch(request)); // A valid identity is not itself authorization.
    assertFalse(broker.authorized(request));
    assertTrue(broker.authorize(request));
    assertTrue(broker.authorized(request));
    var permit = broker.claimDispatch(request);
    assertNotNull(permit);
    assertFalse(broker.authorized(request));
    assertNull(broker.claimDispatch(request));
    broker.revoke("screen");
    assertFalse(permit.isCurrent());
    assertNull(broker.claimDispatch(request));
    // Losing authority after the claim cannot make a possibly performed action replayable.
    assertFalse(
        CapabilityResult.unknown("REVOKED_AFTER_SUBMISSION", "").mayTryAlternateTransport());
  }

  @Test
  public void deadlineExpiresWithoutApprovingOrDispatching() {
    var request = request("screen");
    var confirmation = broker.beginConfirmation(request);
    clock.set(200);
    assertFalse(broker.resolve(confirmation, true));
    assertNull(confirmation.answer());
    assertNull(broker.claimDispatch(request));
  }

  @Test
  public void noRunningInstanceOrWrongInstallationCannotBorrowAuthority() {
    for (var epoch :
        new RuntimeSnapshot.Epoch[] {
          new RuntimeSnapshot.Epoch("install-a", 0, ""),
          new RuntimeSnapshot.Epoch("install-a", 7, ""),
          new RuntimeSnapshot.Epoch("other-install", 7, "runtime-a")
        }) {
      var request =
          CapabilityRequest.create(
              "screen",
              "tap",
              epoch,
              "install-a",
              "bridge-a",
              new ScreenTargetEvidence(0, 12, "com.example.app"),
              0,
              0,
              200);
      assertFalse(broker.current(request));
      assertNull(broker.beginConfirmation(request));
      assertNull(broker.claimDispatch(request));
    }
  }

  @Test
  public void differentBrokerCannotAdoptDispatchPermit() {
    var request = request("screen");
    assertTrue(broker.authorize(request));
    var permit = broker.claimDispatch(request);
    assertFalse(new CapabilityBroker(clock::get).isCurrent(permit));
  }

  @Test
  public void denialAndCancellationCannotBeReauthorizedByLateCallback() {
    var denied = request("screen");
    var prompt = broker.beginConfirmation(denied);
    assertTrue(broker.resolve(prompt, false));
    broker.finish(prompt);
    assertFalse(broker.authorize(denied));
    assertNull(broker.claimDispatch(denied));
    var canceled = request("screen");
    assertTrue(broker.authorize(canceled));
    broker.cancel(canceled);
    assertFalse(broker.authorize(canceled));
    assertNull(broker.claimDispatch(canceled));
  }

  @Test
  public void simultaneousDispatchClaimsHaveOneWinnerAndStopDoesNotWaitOnIt() throws Exception {
    var request = request("screen");
    assertTrue(broker.authorize(request));
    var start = new CountDownLatch(1);
    var finished = new CountDownLatch(16);
    var claims = new AtomicInteger();
    for (int i = 0; i < 16; i++) {
      new Thread(
              () -> {
                try {
                  start.await();
                  if (broker.claimDispatch(request) != null) claims.incrementAndGet();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } finally {
                  finished.countDown();
                }
              })
          .start();
    }
    start.countDown();
    assertTrue(finished.await(3, TimeUnit.SECONDS));
    assertEquals(1, claims.get());
    broker.revokeAll();
    assertFalse(broker.current(request));
    assertFalse(broker.authorize(request));
    assertNull(broker.claimDispatch(request));
  }

  @Test
  public void targetComparisonNeverUsesPackageAlone() {
    var target = new ScreenTargetEvidence(0, 12, "com.example.app");
    assertTrue(target.matches(new ScreenTargetEvidence(0, 12, "com.example.app")));
    assertFalse(target.matches(new ScreenTargetEvidence(1, 12, "com.example.app")));
    assertFalse(target.matches(new ScreenTargetEvidence(0, 13, "com.example.app")));
    assertFalse(target.matches(new ScreenTargetEvidence(0, 12, "com.other.app")));
  }

  @Test
  public void onlyKnownNotSentResultIsEligibleForExplicitAlternateChoice() {
    assertTrue(CapabilityResult.notSent("UNAVAILABLE", "").mayTryAlternateTransport());
    for (var result :
        new CapabilityResult[] {
          CapabilityResult.rejected("DENIED", ""),
          CapabilityResult.completed("OK", ""),
          CapabilityResult.partial("TEXT_INSERTED_SELECTION_FAILED", ""),
          CapabilityResult.unknown("RESULT_LOST", "")
        }) {
      assertFalse(result.mayTryAlternateTransport());
    }
    assertTrue(CapabilityResult.partial("PARTIAL", "").executionMayHaveOccurred());
    assertTrue(CapabilityResult.unknown("UNKNOWN", "").executionMayHaveOccurred());
    assertFalse(CapabilityResult.rejected("DENIED", "").executionMayHaveOccurred());
  }
}
