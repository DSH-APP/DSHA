package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 屏幕常亮与省电模式硬互斥：默认关闭、开一个关另一个、双开归一、重复操作与关闭不误伤。 */
public class PowerModePolicyTest {

  @Test
  public void defaultsBothOffAndNothingToChange() {
    PowerModePolicy.Decision decision = PowerModePolicy.normalizeOnStartup(false, false);
    assertFalse(decision.keepScreenOn);
    assertFalse(decision.ecoMode);
    assertFalse(decision.changed());
    assertFalse(decision.disabledKeepScreenOn);
    assertFalse(decision.disabledEcoMode);
  }

  @Test
  public void startupDoubleOnKeepsScreenOnAndDisablesEco() {
    PowerModePolicy.Decision decision = PowerModePolicy.normalizeOnStartup(true, true);
    assertTrue(decision.keepScreenOn);
    assertFalse(decision.ecoMode);
    assertTrue(decision.changed());
    assertTrue(decision.disabledEcoMode);
    assertFalse(decision.disabledKeepScreenOn);
  }

  @Test
  public void startupSingleSideIsLeftUntouched() {
    PowerModePolicy.Decision keepOnly = PowerModePolicy.normalizeOnStartup(true, false);
    assertTrue(keepOnly.keepScreenOn);
    assertFalse(keepOnly.ecoMode);
    assertFalse(keepOnly.changed());

    PowerModePolicy.Decision ecoOnly = PowerModePolicy.normalizeOnStartup(false, true);
    assertFalse(ecoOnly.keepScreenOn);
    assertTrue(ecoOnly.ecoMode);
    assertFalse(ecoOnly.changed());
  }

  @Test
  public void enablingKeepScreenOnTurnsOffEco() {
    PowerModePolicy.Decision decision = PowerModePolicy.afterKeepScreenOnToggle(false, true, true);
    assertTrue(decision.keepScreenOn);
    assertFalse(decision.ecoMode);
    assertTrue(decision.changed());
    assertTrue(decision.disabledEcoMode);
    assertFalse(decision.disabledKeepScreenOn);
  }

  @Test
  public void enablingEcoTurnsOffKeepScreenOn() {
    PowerModePolicy.Decision decision = PowerModePolicy.afterEcoModeToggle(true, false, true);
    assertFalse(decision.keepScreenOn);
    assertTrue(decision.ecoMode);
    assertTrue(decision.changed());
    assertTrue(decision.disabledKeepScreenOn);
    assertFalse(decision.disabledEcoMode);
  }

  @Test
  public void repeatedEnableOfSameSideIsANoOp() {
    PowerModePolicy.Decision keep = PowerModePolicy.afterKeepScreenOnToggle(true, false, true);
    assertTrue(keep.keepScreenOn);
    assertFalse(keep.ecoMode);
    assertFalse(keep.changed());
    assertFalse(keep.disabledEcoMode);

    PowerModePolicy.Decision eco = PowerModePolicy.afterEcoModeToggle(false, true, true);
    assertFalse(eco.keepScreenOn);
    assertTrue(eco.ecoMode);
    assertFalse(eco.changed());
    assertFalse(eco.disabledKeepScreenOn);
  }

  @Test
  public void disablingOneSideDoesNotTouchTheOther() {
    PowerModePolicy.Decision keepOff = PowerModePolicy.afterKeepScreenOnToggle(true, false, false);
    assertFalse(keepOff.keepScreenOn);
    assertFalse(keepOff.ecoMode);
    assertTrue(keepOff.changed());
    assertFalse(keepOff.disabledKeepScreenOn);
    assertFalse(keepOff.disabledEcoMode);

    PowerModePolicy.Decision ecoOff = PowerModePolicy.afterEcoModeToggle(false, true, false);
    assertFalse(ecoOff.keepScreenOn);
    assertFalse(ecoOff.ecoMode);
    assertTrue(ecoOff.changed());
    assertFalse(ecoOff.disabledKeepScreenOn);
    assertFalse(ecoOff.disabledEcoMode);

    PowerModePolicy.Decision alreadyOff =
        PowerModePolicy.afterKeepScreenOnToggle(false, false, false);
    assertFalse(alreadyOff.changed());
    assertFalse(alreadyOff.disabledEcoMode);
  }

  @Test
  public void eitherToggleOrderEndsWithExactlyOneSideOn() {
    PowerModePolicy.Decision keepFirst =
        PowerModePolicy.afterKeepScreenOnToggle(false, false, true);
    PowerModePolicy.Decision keepThenEco =
        PowerModePolicy.afterEcoModeToggle(
            keepFirst.keepScreenOn, keepFirst.ecoMode, /* checked= */ true);
    assertFalse(keepThenEco.keepScreenOn);
    assertTrue(keepThenEco.ecoMode);
    assertTrue(keepThenEco.disabledKeepScreenOn);

    PowerModePolicy.Decision ecoFirst = PowerModePolicy.afterEcoModeToggle(false, false, true);
    PowerModePolicy.Decision ecoThenKeep =
        PowerModePolicy.afterKeepScreenOnToggle(
            ecoFirst.keepScreenOn, ecoFirst.ecoMode, /* checked= */ true);
    assertTrue(ecoThenKeep.keepScreenOn);
    assertFalse(ecoThenKeep.ecoMode);
    assertTrue(ecoThenKeep.disabledEcoMode);

    assertEquals(1, onCount(keepThenEco));
    assertEquals(1, onCount(ecoThenKeep));
  }

  @Test
  public void togglingFromEveryHistoricalStateNeverLeavesBothOn() {
    boolean[] values = {false, true};
    for (boolean keep : values) {
      for (boolean eco : values) {
        assertEquals(1, onCount(PowerModePolicy.afterKeepScreenOnToggle(keep, eco, true)));
        assertEquals(1, onCount(PowerModePolicy.afterEcoModeToggle(keep, eco, true)));
      }
    }
  }

  @Test
  public void disablingEitherSideNeverEnablesTheOther() {
    boolean[] values = {false, true};
    for (boolean keep : values) {
      for (boolean eco : values) {
        PowerModePolicy.Decision keepOff =
            PowerModePolicy.afterKeepScreenOnToggle(keep, eco, false);
        assertFalse(keepOff.keepScreenOn);
        assertEquals(eco, keepOff.ecoMode);

        PowerModePolicy.Decision ecoOff = PowerModePolicy.afterEcoModeToggle(keep, eco, false);
        assertEquals(keep, ecoOff.keepScreenOn);
        assertFalse(ecoOff.ecoMode);
      }
    }
  }

  private static int onCount(PowerModePolicy.Decision decision) {
    int count = 0;
    if (decision.keepScreenOn) count++;
    if (decision.ecoMode) count++;
    return count;
  }
}
