package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.ScreenCapabilityGate.Inputs;
import com.deepseekharness.app.util.ScreenCapabilityGate.Shizuku;
import java.util.List;
import org.junit.Test;

public final class ScreenCapabilityGateTest {
  private static Inputs in(
      int api,
      boolean a11y,
      boolean root,
      Shizuku shizuku,
      boolean adbEnabled,
      boolean lan,
      boolean adbConnected) {
    return new Inputs(api, a11y, root, root, shizuku, adbEnabled, lan, adbConnected, true);
  }

  @Test
  public void physicalNeedsOnlyAccessibility() {
    var yes = ScreenCapabilityGate.physical(in(34, true, false, Shizuku.NONE, false, false, false));
    assertTrue(yes.allowed);
    assertEquals("", yes.channel);
    var no = ScreenCapabilityGate.physical(in(34, false, true, Shizuku.READY, true, true, true));
    assertFalse(no.allowed);
    assertEquals(List.of(ScreenCapabilityGate.MISSING_ACCESSIBILITY), no.missing);
  }

  @Test
  public void virtualNeedsARealChannelAndTreatsAccessibilityAsEnhancement() {
    var v = ScreenCapabilityGate.virtual(in(34, false, false, Shizuku.READY, false, false, false));
    assertTrue(v.allowed);
    assertEquals("shizuku", v.channel);
    assertEquals(List.of(ScreenCapabilityGate.MISSING_ACCESSIBILITY), v.degraded);
    var none = ScreenCapabilityGate.virtual(in(34, true, false, Shizuku.NONE, false, false, false));
    assertFalse(none.allowed);
    assertEquals(List.of(ScreenCapabilityGate.MISSING_DEVICE_CHANNEL), none.missing);
    assertEquals("", none.channel);
  }

  @Test
  public void permissionAloneOrAdbSwitchAloneIsNotUsable() {
    var permitted = in(34, true, false, Shizuku.PERMITTED, true, true, false);
    assertFalse(ScreenCapabilityGate.virtual(permitted).allowed);
    assertTrue(ScreenCapabilityGate.channelAuthorized("shizuku", permitted));
    assertTrue(ScreenCapabilityGate.channelAuthorized("adb", permitted));
    assertFalse(ScreenCapabilityGate.channelUsable("adb", permitted));
    // 已验证连接但缺局域网授权也不可用。
    assertFalse(
        ScreenCapabilityGate.channelUsable(
            "adb", in(34, true, false, Shizuku.NONE, true, false, true)));
  }

  @Test
  public void rootIsPreferredAndRequiresBothEnabledAndPresent() {
    var all = in(37, true, true, Shizuku.READY, true, true, true);
    assertEquals("root", ScreenCapabilityGate.chooseUsable(all));
    var absent = new Inputs(34, true, true, false, Shizuku.NONE, false, false, false, true);
    assertFalse(ScreenCapabilityGate.channelUsable("root", absent));
  }

  @Test
  public void android17PrefersVerifiedAdbOverShizuku() {
    assertEquals(
        "adb",
        ScreenCapabilityGate.chooseUsable(in(37, true, false, Shizuku.READY, true, true, true)));
    assertEquals(
        "shizuku",
        ScreenCapabilityGate.chooseUsable(in(36, true, false, Shizuku.READY, true, true, true)));
    assertEquals(List.of("root", "shizuku", "adb"), ScreenCapabilityGate.order(37, false));
  }

  @Test
  public void virtualRequiresApi30AndReportsMissingOverlayAsDegraded() {
    var old = new Inputs(29, true, true, true, Shizuku.NONE, false, false, false, false);
    var v = ScreenCapabilityGate.virtual(old);
    assertFalse(v.allowed);
    assertTrue(v.missing.contains(ScreenCapabilityGate.MISSING_API));
    assertTrue(v.degraded.contains(ScreenCapabilityGate.MISSING_OVERLAY));
    assertTrue(
        ScreenCapabilityGate.physical(old).degraded.contains(ScreenCapabilityGate.MISSING_OVERLAY));
  }

  @Test
  public void unknownChannelIsNeverUsable() {
    var all = in(34, true, true, Shizuku.READY, true, true, true);
    assertFalse(ScreenCapabilityGate.channelUsable("su", all));
    assertFalse(ScreenCapabilityGate.channelAuthorized(null, all));
  }
}
