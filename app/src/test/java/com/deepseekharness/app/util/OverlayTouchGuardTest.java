package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class OverlayTouchGuardTest {
  private final AtomicLong now = new AtomicLong(10_000);
  private final OverlayTouchGuard guard = new OverlayTouchGuard(now::get);

  @Test
  public void tapsDuringLayoutSettleAreIgnored() {
    assertTrue(guard.accept());
    guard.layoutChanged();
    assertFalse(guard.accept());
    now.addAndGet(OverlayTouchGuard.SETTLE_MILLIS - 1);
    assertFalse(guard.accept());
    assertEquals(OverlayTouchGuard.StopTap.IGNORED, guard.stop());
    now.addAndGet(1);
    assertTrue(guard.accept());
  }

  @Test
  public void stopNeedsASecondTapInsideTheConfirmWindow() {
    assertEquals(OverlayTouchGuard.StopTap.ARMED, guard.stop());
    assertTrue(guard.stopArmed());
    now.addAndGet(OverlayTouchGuard.CONFIRM_MILLIS);
    assertEquals(OverlayTouchGuard.StopTap.CONFIRMED, guard.stop());
    assertFalse(guard.stopArmed());
    // 确认后重新开始，需要再次两步。
    assertEquals(OverlayTouchGuard.StopTap.ARMED, guard.stop());
  }

  @Test
  public void expiredOtherButtonOrLayoutChangeDisarmsStop() {
    assertEquals(OverlayTouchGuard.StopTap.ARMED, guard.stop());
    now.addAndGet(OverlayTouchGuard.CONFIRM_MILLIS + 1);
    assertFalse(guard.stopArmed());
    assertEquals(OverlayTouchGuard.StopTap.ARMED, guard.stop());
    assertTrue(guard.accept());
    assertFalse(guard.stopArmed());
    assertEquals(OverlayTouchGuard.StopTap.ARMED, guard.stop());
    guard.layoutChanged();
    assertFalse(guard.stopArmed());
    now.addAndGet(OverlayTouchGuard.SETTLE_MILLIS);
    assertEquals(OverlayTouchGuard.StopTap.ARMED, guard.stop());
  }

  @Test
  public void clockRegressionNeverConfirms() {
    assertEquals(OverlayTouchGuard.StopTap.ARMED, guard.stop());
    now.addAndGet(-5);
    assertFalse(guard.stopArmed());
    assertEquals(OverlayTouchGuard.StopTap.ARMED, guard.stop());
  }

  @Test(expected = IllegalArgumentException.class)
  public void missingClockIsRejected() {
    new OverlayTouchGuard(null);
  }
}
