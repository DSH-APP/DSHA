package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class ConfirmCountdownTest {
  @Test
  public void countsDownInWholeSecondsRoundedUp() {
    assertEquals(10, ConfirmCountdown.remainingSeconds(1_000, 1_000, 10));
    assertEquals(10, ConfirmCountdown.remainingSeconds(1_000, 1_001, 10));
    assertEquals(9, ConfirmCountdown.remainingSeconds(1_000, 2_000, 10));
    assertEquals(1, ConfirmCountdown.remainingSeconds(1_000, 10_999, 10));
  }

  @Test
  public void opensOnlyAfterTheFullWait() {
    assertEquals(0, ConfirmCountdown.remainingSeconds(1_000, 11_000, 10));
    assertEquals(0, ConfirmCountdown.remainingSeconds(1_000, 60_000, 10));
  }

  @Test
  public void clockGoingBackwardsNeverShortensTheWait() {
    assertEquals(10, ConfirmCountdown.remainingSeconds(5_000, 1_000, 10));
  }

  @Test
  public void defaultRootRelaxWaitIsTenSeconds() {
    assertEquals(10, ConfirmCountdown.ROOT_RELAX_SECONDS);
    assertEquals(0, ConfirmCountdown.remainingSeconds(0, 0, 0));
  }
}
