package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdbProbeDeferralTest {
  @Test
  public void noEntryCheckRunsImmediately() {
    assertEquals(0L, AdbProbeDeferral.delayMs(1_000L, 0L, 1_000L));
    assertFalse(AdbProbeDeferral.entryActive(0L, 5_000L));
  }

  @Test
  public void activeEntryCheckDefersProbe() {
    long entry = 10_000L;
    assertTrue(AdbProbeDeferral.entryActive(entry, entry + 1_000L));
    assertEquals(
        AdbProbeDeferral.RETRY_MS, AdbProbeDeferral.delayMs(entry + 500L, entry, entry + 500L));
  }

  @Test
  public void staleEntryWindowExpires() {
    long entry = 10_000L;
    long now = entry + AdbProbeDeferral.ENTRY_WINDOW_MAX_MS;
    assertFalse(AdbProbeDeferral.entryActive(entry, now));
    assertEquals(0L, AdbProbeDeferral.delayMs(now, entry, now));
  }

  @Test
  public void clockGoingBackwardsDoesNotKeepWindowOpen() {
    assertFalse(AdbProbeDeferral.entryActive(10_000L, 9_000L));
  }

  @Test
  public void totalDeferralIsBounded() {
    long first = 10_000L;
    long entry = first;
    // 窗口被反复刷新也不能无限推迟：累计达到上限后立即执行。
    long now = first + AdbProbeDeferral.MAX_DEFER_MS;
    assertEquals(0L, AdbProbeDeferral.delayMs(first, now - 1L, now));
    // 接近上限时只等剩余时间。
    long near = first + AdbProbeDeferral.MAX_DEFER_MS - 500L;
    assertEquals(500L, AdbProbeDeferral.delayMs(first, entry + 20_000L, near));
  }
}
