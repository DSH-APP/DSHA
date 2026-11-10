package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.ScreenCapabilityGate.Inputs;
import com.deepseekharness.app.util.ScreenCapabilityGate.Shizuku;
import com.deepseekharness.app.util.ScreenModePolicy.Domain;
import com.deepseekharness.app.util.ScreenModePolicy.Mode;
import com.deepseekharness.app.util.ScreenModePolicy.Switch;
import org.junit.Test;

public final class ScreenModePolicyTest {
  @Test
  public void bridgeAllowsOnlyTheMatchingScreenAndNeverFallsBack() {
    assertEquals(
        ScreenModePolicy.SCREEN_MODE_OFF, ScreenModePolicy.rejection(Mode.OFF, Domain.PHYSICAL));
    assertEquals(
        ScreenModePolicy.SCREEN_MODE_OFF, ScreenModePolicy.rejection(Mode.OFF, Domain.VIRTUAL));
    assertNull(ScreenModePolicy.rejection(Mode.PHYSICAL, Domain.PHYSICAL));
    assertEquals(
        ScreenModePolicy.SCREEN_MODE_VIRTUAL_OFF,
        ScreenModePolicy.rejection(Mode.PHYSICAL, Domain.VIRTUAL));
    assertNull(ScreenModePolicy.rejection(Mode.VIRTUAL, Domain.VIRTUAL));
    assertEquals(
        ScreenModePolicy.SCREEN_MODE_PHYSICAL_OFF,
        ScreenModePolicy.rejection(Mode.VIRTUAL, Domain.PHYSICAL));
    assertEquals(
        ScreenModePolicy.SCREEN_MODE_OFF, ScreenModePolicy.rejection(null, Domain.VIRTUAL));
  }

  @Test
  public void onlyTaskEndCleanupIsExempt() {
    assertTrue(ScreenModePolicy.exempt(Domain.PHYSICAL, "task-end"));
    assertFalse(ScreenModePolicy.exempt(Domain.PHYSICAL, "task-begin"));
    assertFalse(ScreenModePolicy.exempt(Domain.VIRTUAL, "close"));
    assertFalse(ScreenModePolicy.exempt(Domain.VIRTUAL, "task-end"));
  }

  @Test
  public void parseIsFailClosed() {
    assertEquals(Mode.OFF, Mode.parse(null));
    assertEquals(Mode.OFF, Mode.parse("PHYSICAL"));
    assertEquals(Mode.PHYSICAL, Mode.parse("physical"));
    assertEquals(Mode.VIRTUAL, Mode.parse(Mode.VIRTUAL.wire()));
  }

  @Test
  public void migrationKeepsExistingUsage() {
    assertEquals(Mode.VIRTUAL, ScreenModePolicy.migrate(true, true));
    assertEquals(Mode.PHYSICAL, ScreenModePolicy.migrate(false, true));
    assertEquals(Mode.OFF, ScreenModePolicy.migrate(false, false));
  }

  @Test
  public void switchingRules() {
    assertEquals(
        Switch.UNCHANGED, ScreenModePolicy.evaluate(Mode.VIRTUAL, Mode.VIRTUAL, false, true));
    assertEquals(
        Switch.CLEANUP_PENDING, ScreenModePolicy.evaluate(Mode.VIRTUAL, Mode.OFF, true, true));
    assertEquals(
        Switch.CLEANUP_PENDING, ScreenModePolicy.evaluate(Mode.VIRTUAL, Mode.PHYSICAL, true, true));
    assertEquals(
        Switch.NOT_ADMITTED, ScreenModePolicy.evaluate(Mode.OFF, Mode.VIRTUAL, false, false));
    assertEquals(Switch.ALLOWED, ScreenModePolicy.evaluate(Mode.PHYSICAL, Mode.OFF, false, false));
    assertEquals(
        Switch.ALLOWED, ScreenModePolicy.evaluate(Mode.PHYSICAL, Mode.VIRTUAL, true, false));
    // 实体模式不受虚拟屏待清理影响（待清理只阻止离开 virtual）。
    assertEquals(Switch.ALLOWED, ScreenModePolicy.evaluate(Mode.OFF, Mode.PHYSICAL, true, true));
  }

  @Test
  public void lostAuthorizationDowngradesToOffAndNeverSwitchesMode() {
    assertEquals(Mode.OFF, ScreenModePolicy.afterCapabilityChange(Mode.VIRTUAL, false));
    assertEquals(Mode.OFF, ScreenModePolicy.afterCapabilityChange(Mode.PHYSICAL, false));
    assertEquals(Mode.VIRTUAL, ScreenModePolicy.afterCapabilityChange(Mode.VIRTUAL, true));
    assertEquals(Mode.OFF, ScreenModePolicy.afterCapabilityChange(Mode.OFF, true));
  }

  @Test
  public void retentionIsLooserThanAdmission() {
    // Shizuku 已授权但用户服务短暂未绑定：不能新开 virtual，但已开启的 virtual 保持。
    var rebinding =
        new Inputs(34, false, false, false, Shizuku.PERMITTED, false, false, false, true);
    assertFalse(ScreenModePolicy.admitted(Mode.VIRTUAL, rebinding));
    assertTrue(ScreenModePolicy.retained(Mode.VIRTUAL, rebinding));
    var adbReconnecting =
        new Inputs(34, false, false, false, Shizuku.NONE, true, true, false, true);
    assertTrue(ScreenModePolicy.retained(Mode.VIRTUAL, adbReconnecting));
    var nothing = new Inputs(34, false, false, false, Shizuku.NONE, false, true, false, true);
    assertFalse(ScreenModePolicy.retained(Mode.VIRTUAL, nothing));
    assertFalse(ScreenModePolicy.retained(Mode.PHYSICAL, nothing));
    assertTrue(ScreenModePolicy.retained(Mode.OFF, nothing));
  }
}
