package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.GrantSummary.Label;
import com.deepseekharness.app.util.GrantSummary.Tone;
import org.junit.Test;

/** 概览胶囊只做状态映射：高风险档位不能被「正常」掩盖，读不到状态时不能显示成已关闭。 */
public final class GrantSummaryTest {
  private static void assertItem(GrantSummary.Item item, Label label, Tone tone) {
    assertEquals(label, item.label);
    assertEquals(tone, item.tone);
  }

  @Test
  public void globalOnIsHighestRiskAndUnreadableIsNotReportedAsOff() {
    assertItem(GrantSummary.global(NativeConsentPolicy.ReadState.ON), Label.GLOBAL_ON, Tone.BAD);
    assertItem(GrantSummary.global(NativeConsentPolicy.ReadState.OFF), Label.GLOBAL_OFF, Tone.GOOD);
    assertItem(
        GrantSummary.global(NativeConsentPolicy.ReadState.UNCONFIGURED),
        Label.GLOBAL_OFF,
        Tone.GOOD);
    for (var state :
        new NativeConsentPolicy.ReadState[] {
          NativeConsentPolicy.ReadState.MALFORMED,
          NativeConsentPolicy.ReadState.READ_UNAVAILABLE,
          NativeConsentPolicy.ReadState.SAVE_FAILED,
          NativeConsentPolicy.ReadState.RECONFIRM_REQUIRED
        }) assertItem(GrantSummary.global(state), Label.GLOBAL_ATTENTION, Tone.WARN);
    assertItem(
        GrantSummary.global(NativeConsentPolicy.ReadState.CHANGING),
        Label.GLOBAL_UPDATING,
        Tone.NEUTRAL);
    assertItem(GrantSummary.global(null), Label.GLOBAL_ATTENTION, Tone.WARN);
  }

  @Test
  public void everyConsentStateIsMapped() {
    for (var state : NativeConsentPolicy.ReadState.values())
      assertNotNull(GrantSummary.global(state).label);
  }

  @Test
  public void relaxedRootIsShownEvenWhenSuIsMissing() {
    assertItem(
        GrantSummary.root(false, true, DeviceShellPolicy.Mode.FULL), Label.ROOT_OFF, Tone.NEUTRAL);
    assertItem(
        GrantSummary.root(true, false, DeviceShellPolicy.Mode.FULL), Label.ROOT_FULL, Tone.BAD);
    assertItem(
        GrantSummary.root(true, false, DeviceShellPolicy.Mode.RELAXED),
        Label.ROOT_RELAXED,
        Tone.WARN);
    assertItem(
        GrantSummary.root(true, false, DeviceShellPolicy.Mode.STRICT), Label.ROOT_NO_SU, Tone.WARN);
    assertItem(
        GrantSummary.root(true, true, DeviceShellPolicy.Mode.STRICT), Label.ROOT_STRICT, Tone.GOOD);
  }

  @Test
  public void everyShizukuStepIsMapped() {
    for (var step : ShizukuChannelStatus.Step.values())
      assertNotNull(GrantSummary.shizuku(step).label);
    assertItem(
        GrantSummary.shizuku(ShizukuChannelStatus.Step.READY), Label.SHIZUKU_READY, Tone.GOOD);
    assertItem(
        GrantSummary.shizuku(ShizukuChannelStatus.Step.AUTHORIZE_IN_OWNER),
        Label.SHIZUKU_NOT_AUTHORIZED,
        Tone.WARN);
    assertItem(
        GrantSummary.shizuku(ShizukuChannelStatus.Step.BIND_FAILED),
        Label.SHIZUKU_FAILED,
        Tone.WARN);
  }

  @Test
  public void screenPauseWinsOverModeAndAllowAllIsWarned() {
    var all = ScreenConfirmationPolicy.Mode.ALLOW_ALL;
    assertItem(GrantSummary.screen(false, false, false, all), Label.SCREEN_NOT_SET, Tone.WARN);
    assertItem(GrantSummary.screen(true, false, false, all), Label.SCREEN_CONNECTING, Tone.NEUTRAL);
    assertItem(GrantSummary.screen(true, true, true, all), Label.SCREEN_PAUSED, Tone.WARN);
    assertItem(GrantSummary.screen(true, true, false, all), Label.SCREEN_ALLOW_ALL, Tone.WARN);
    assertItem(
        GrantSummary.screen(true, true, false, ScreenConfirmationPolicy.Mode.CUSTOM),
        Label.SCREEN_CUSTOM,
        Tone.GOOD);
    assertItem(
        GrantSummary.screen(true, true, false, ScreenConfirmationPolicy.Mode.MANUAL),
        Label.SCREEN_MANUAL,
        Tone.GOOD);
  }

  @Test
  public void columnsAndCenteredPaddingFollowContentWidth() {
    assertEquals(1, GrantSummary.columns(360));
    assertEquals(1, GrantSummary.columns(639));
    assertEquals(2, GrantSummary.columns(640));
    // 手机与平板竖屏单栏：保持页面边距。
    assertEquals(14, GrantSummary.sidePadding(360, 14));
    assertEquals(14, GrantSummary.sidePadding(600, 14));
    // 双栏：宽度不超过上限，超出部分两侧均分，且仍判为双栏。
    assertEquals(14, GrantSummary.sidePadding(800, 14));
    int padding = GrantSummary.sidePadding(1500, 14);
    assertEquals(230, padding);
    assertEquals(2, GrantSummary.columns(1500 - 2 * padding));
  }
}
