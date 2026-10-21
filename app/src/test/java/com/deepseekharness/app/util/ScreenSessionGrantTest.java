package com.deepseekharness.app.util;

import org.junit.Test;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.capabilities.ScreenTargetEvidence;
import com.deepseekharness.app.core.RuntimeSnapshot;
import static org.junit.Assert.*;

public class ScreenSessionGrantTest {
  private static CapabilityRequest request(
      RuntimeSnapshot.Epoch runtime, String bridge, long policy, long authority) {
    return CapabilityRequest.create(
        "screen",
        "tap",
        runtime,
        runtime.ownerId,
        bridge,
        new ScreenTargetEvidence(0, 1, "com.example.app"),
        policy,
        authority,
        1000);
  }

  @Test
  public void typedGrantBindsWholeRuntimeBridgePolicyAndAuthority() {
    var grant = new ScreenSessionGrant();
    var runtime = new RuntimeSnapshot.Epoch("install-a", 7, "runtime-a");
    var request = request(runtime, "bridge-a", 3, 8);
    assertTrue(
        grant.completeConfirmation(
            true, request, runtime, "bridge-a", grant.revision(), 3, true, true));
    assertTrue(grant.allowed(request));
    assertFalse(
        grant.allowed(
            request(new RuntimeSnapshot.Epoch("install-a", 7, "runtime-b"), "bridge-a", 3, 8)));
    assertFalse(grant.allowed(request(runtime, "bridge-b", 3, 8)));
    assertFalse(grant.allowed(request(runtime, "bridge-a", 4, 8)));
    assertFalse(grant.allowed(request(runtime, "bridge-a", 3, 9)));
    grant.revoke();
    assertFalse(grant.allowed(request));
  }

  @Test
  public void typedOneShotAndOldApprovalCannotResurrectAfterRevoke() {
    var grant = new ScreenSessionGrant();
    var runtime = new RuntimeSnapshot.Epoch("install-a", 7, "runtime-a");
    var request = request(runtime, "bridge-a", 3, 8);
    long oldRevision = grant.revision();
    assertTrue(
        grant.completeConfirmation(
            true, request, runtime, "bridge-a", oldRevision, 3, true, false));
    assertFalse(grant.allowed(request));
    grant.revoke();
    assertFalse(
        grant.completeConfirmation(true, request, runtime, "bridge-a", oldRevision, 3, true, true));
    assertFalse(
        grant.completeConfirmation(
            true, request, runtime, "bridge-a", grant.revision(), 4, true, true));
    assertFalse(
        grant.completeConfirmation(
            true, request, runtime, "bridge-a", grant.revision(), 3, false, true));
  }

  @Test
  public void allowAllPolicyCannotOverridePausedTypedGrant() {
    var grant = new ScreenSessionGrant();
    var policy =
        new ScreenConfirmationPolicy(
            ScreenConfirmationPolicy.Mode.ALLOW_ALL, java.util.Map.of(), 3);
    var runtime = new RuntimeSnapshot.Epoch("install-a", 7, "runtime-a");
    var request = request(runtime, "bridge-a", policy.revision, 8);
    assertTrue(policy.allows("com.example.sensitive"));
    grant.setPaused(true);
    assertFalse(
        grant.completeConfirmation(
            true, request, runtime, "bridge-a", grant.revision(), policy.revision, true, true));
    assertFalse(grant.allowed(request));
    grant.setPaused(false);
    assertTrue(
        grant.completeConfirmation(
            true, request, runtime, "bridge-a", grant.revision(), policy.revision, true, true));
  }

  @Test
  public void pauseBlocksAutomaticAndManualApprovalsUntilExplicitResume() {
    var grant = new ScreenSessionGrant();
    long queued = grant.revision();
    grant.setPaused(true);
    grant.revoke(); // stopping a run must not silently resume a user's pause
    assertTrue(grant.paused());
    assertFalse(grant.completeConfirmation(true, 7, 7, grant.revision(), true, true));
    grant.setPaused(false);
    assertFalse(grant.completeConfirmation(true, 7, 7, queued, true, true));
    assertTrue(grant.completeConfirmation(true, 8, 8, grant.revision(), true, true));
    assertFalse(grant.allowed(7));
  }

  @Test
  public void grantLastsOnlyForTheSameRun() {
    ScreenSessionGrant grant = new ScreenSessionGrant();
    assertFalse(grant.allowed(1));
    assertFalse(grant.accept(0, 0));
    assertTrue(grant.accept(1, grant.revision()));
    assertTrue(grant.allowed(1));
    assertFalse(grant.allowed(2));
  }

  @Test
  public void revokeRejectsPendingConfirmation() {
    ScreenSessionGrant grant = new ScreenSessionGrant();
    long pending = grant.revision();
    grant.revoke();
    assertFalse(grant.accept(1, pending));
    assertFalse(grant.allowed(1));
    assertTrue(grant.accept(1, grant.revision()));
    grant.revoke();
    assertFalse(grant.allowed(1));
  }

  @Test
  public void sensitiveApprovalIsOneShotAndDoesNotRememberTheRun() {
    ScreenSessionGrant grant = new ScreenSessionGrant();
    assertTrue(grant.completeConfirmation(true, 7, 7, grant.revision(), true, false));
    assertFalse(grant.allowed(7));
    assertFalse(grant.completeConfirmation(false, 7, 7, grant.revision(), true, false));
    assertTrue(grant.completeConfirmation(true, 7, 7, grant.revision(), true, true));
    assertTrue(grant.allowed(7));
  }

  @Test
  public void sensitiveAndRememberedApprovalsBothRejectRevocationDuringDialog() {
    for (boolean remember : new boolean[] {false, true}) {
      ScreenSessionGrant grant = new ScreenSessionGrant();
      long pending = grant.revision();
      grant.revoke(); // Manual revoke and accessibility disconnect use this same transition.
      assertFalse(grant.completeConfirmation(true, 7, 7, pending, true, remember));
      assertFalse(grant.allowed(7));
    }
  }

  @Test
  public void stopAndReplacementRunCannotConsumeOldApproval() {
    for (boolean remember : new boolean[] {false, true}) {
      ScreenSessionGrant grant = new ScreenSessionGrant();
      long pending = grant.revision();
      assertFalse(grant.completeConfirmation(true, 7, 7, pending, false, remember));
      assertFalse(grant.completeConfirmation(true, 7, 8, pending, true, remember));
      assertFalse(grant.completeConfirmation(true, 0, 0, pending, true, remember));
      assertFalse(grant.allowed(8));
    }
  }
}
