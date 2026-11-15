package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class BatteryBackgroundStateTest {
  @Test
  public void exemptionWithoutRestrictionIsUnrestricted() {
    var state = BatteryBackgroundState.of(true, false);
    assertEquals(BatteryBackgroundState.State.UNRESTRICTED, state);
    assertTrue(BatteryBackgroundState.granted(state));
  }

  @Test
  public void allowedBackgroundUsageCountsAsEnabledEvenWithoutExemption() {
    // Android 14+“允许后台使用”开关默认是“优化”档：未受限、未豁免，用户已允许后台。
    var state = BatteryBackgroundState.of(false, false);
    assertEquals(BatteryBackgroundState.State.ALLOWED, state);
    assertTrue(BatteryBackgroundState.granted(state));
  }

  @Test
  public void explicitRestrictionWinsOverExemption() {
    assertEquals(BatteryBackgroundState.State.RESTRICTED, BatteryBackgroundState.of(false, true));
    assertEquals(BatteryBackgroundState.State.RESTRICTED, BatteryBackgroundState.of(true, true));
    assertFalse(BatteryBackgroundState.granted(BatteryBackgroundState.State.RESTRICTED));
  }
}
