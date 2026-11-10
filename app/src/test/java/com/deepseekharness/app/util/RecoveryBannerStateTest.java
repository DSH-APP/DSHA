package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecoveryBannerStateTest {
  @Test
  public void capsuleHidesTheBannerEvenWhenOtherReasonsApply() {
    assertEquals(
        RecoveryBannerState.Kind.NONE, RecoveryBannerState.decide(true, true, true, true, true));
  }

  @Test
  public void busyBeatsLimitedAndStartup() {
    assertEquals(
        RecoveryBannerState.Kind.BUSY, RecoveryBannerState.decide(false, true, false, true, true));
    assertEquals(
        RecoveryBannerState.Kind.AWAITING_CONFIRMATION,
        RecoveryBannerState.decide(false, true, true, true, true));
    assertEquals(
        RecoveryBannerState.Kind.LIMITED,
        RecoveryBannerState.decide(false, false, false, true, true));
    assertEquals(
        RecoveryBannerState.Kind.STARTUP,
        RecoveryBannerState.decide(false, false, false, false, true));
    assertEquals(
        RecoveryBannerState.Kind.NONE,
        RecoveryBannerState.decide(false, false, false, false, false));
  }

  @Test
  public void toneAndSpinnerFollowTheKind() {
    assertEquals(
        RecoveryBannerState.Tone.ACCENT, RecoveryBannerState.tone(RecoveryBannerState.Kind.BUSY));
    assertEquals(
        RecoveryBannerState.Tone.WARN, RecoveryBannerState.tone(RecoveryBannerState.Kind.LIMITED));
    assertEquals(
        RecoveryBannerState.Tone.WARN,
        RecoveryBannerState.tone(RecoveryBannerState.Kind.AWAITING_CONFIRMATION));
    assertEquals(
        RecoveryBannerState.Tone.BAD, RecoveryBannerState.tone(RecoveryBannerState.Kind.STARTUP));
    assertTrue(RecoveryBannerState.spinning(RecoveryBannerState.Kind.BUSY));
    assertFalse(RecoveryBannerState.spinning(RecoveryBannerState.Kind.LIMITED));
    assertFalse(RecoveryBannerState.spinning(RecoveryBannerState.Kind.STARTUP));
    assertFalse(RecoveryBannerState.spinning(RecoveryBannerState.Kind.AWAITING_CONFIRMATION));
  }
}
