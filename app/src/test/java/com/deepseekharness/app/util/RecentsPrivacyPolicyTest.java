package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.RecentsPrivacyPolicy.BackgroundAction;
import org.junit.Test;

/** 「隐藏后台」截图遮挡与严格模式的纯策略；不冒充真机最近任务行为。 */
public final class RecentsPrivacyPolicyTest {
  @Test
  public void screenshotIsHiddenOnlyWhileThePreferenceIsOn() {
    assertTrue(RecentsPrivacyPolicy.hideRecentsScreenshot(true));
    assertFalse(RecentsPrivacyPolicy.hideRecentsScreenshot(false));
  }

  @Test
  public void strictModeRequiresTheParentPreference() {
    assertFalse(RecentsPrivacyPolicy.strictAvailable(false));
    assertTrue(RecentsPrivacyPolicy.strictAvailable(true));
    assertEquals(
        BackgroundAction.KEEP,
        RecentsPrivacyPolicy.onAppBackground(false, true, false, false, false));
    assertEquals(
        BackgroundAction.KEEP,
        RecentsPrivacyPolicy.onAppBackground(true, false, false, false, false));
  }

  @Test
  public void strictModeEndsTheUiTaskAfterTheAppLeavesTheForeground() {
    assertEquals(
        BackgroundAction.END_TASK,
        RecentsPrivacyPolicy.onAppBackground(true, true, false, false, false));
  }

  @Test
  public void externalPickerInFlightNeverEndsTheTask() {
    assertEquals(
        BackgroundAction.KEEP,
        RecentsPrivacyPolicy.onAppBackground(true, true, true, false, false));
  }

  @Test
  public void pictureInPictureAndMultiWindowKeepTheUsersVisibleUi() {
    assertEquals(
        BackgroundAction.KEEP,
        RecentsPrivacyPolicy.onAppBackground(true, true, false, true, false));
    assertEquals(
        BackgroundAction.KEEP,
        RecentsPrivacyPolicy.onAppBackground(true, true, false, false, true));
  }
}
