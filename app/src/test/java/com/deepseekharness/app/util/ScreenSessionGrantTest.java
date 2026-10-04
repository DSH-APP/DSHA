package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenSessionGrantTest {
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
