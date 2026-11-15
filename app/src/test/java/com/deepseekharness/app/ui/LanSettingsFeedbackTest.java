package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;

import com.deepseekharness.app.R;
import org.junit.Test;

/** A submitted asynchronous start is never presented as a confirmed listener. */
public final class LanSettingsFeedbackTest {
  @Test
  public void configurationSaveUsesStatusPromptRatherThanPasswordSavedOrSuccess() {
    assertEquals(R.string.lan_apply_check, ConfigFragment.lanSettingsMessage(true, true, true));
    assertEquals(R.string.lan_apply_failed, ConfigFragment.lanSettingsMessage(true, true, false));
    assertEquals(
        R.string.lan_password_missing, ConfigFragment.lanSettingsMessage(true, false, true));
    assertEquals(0, ConfigFragment.lanSettingsMessage(false, true, true));
  }

  @Test
  public void passwordSaveDistinguishesPersistenceSubmissionAndDisabledLan() {
    assertEquals(
        R.string.lan_password_failed, ConfigFragment.lanPasswordSavedMessage(false, true, false));
    assertEquals(
        R.string.lan_password_listener_failed,
        ConfigFragment.lanPasswordSavedMessage(true, true, false));
    assertEquals(
        R.string.lan_password_saved_check,
        ConfigFragment.lanPasswordSavedMessage(true, true, true));
    assertEquals(
        R.string.lan_password_waiting, ConfigFragment.lanPasswordSavedMessage(true, false, true));
  }
}
