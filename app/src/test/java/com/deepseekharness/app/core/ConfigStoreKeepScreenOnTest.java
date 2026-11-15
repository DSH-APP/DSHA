package com.deepseekharness.app.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.deepseekharness.app.util.Constants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 屏幕常亮是本机窗口偏好，默认关闭且不进入主机备份设置。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class ConfigStoreKeepScreenOnTest {
  private SharedPreferences preferences;

  @Before
  public void clearKeepScreenOnPreference() {
    preferences =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    preferences.edit().remove(Constants.KEY_KEEP_SCREEN_ON).commit();
  }

  @After
  public void restoreKeepScreenOnPreference() {
    preferences.edit().remove(Constants.KEY_KEEP_SCREEN_ON).commit();
  }

  @Test
  public void missingPreferenceDefaultsToOff() {
    assertFalse(new ConfigStore(RuntimeEnvironment.getApplication()).isKeepScreenOn());
  }

  @Test
  public void enabledPreferenceSurvivesAConfigStoreReload() {
    ConfigStore first = new ConfigStore(RuntimeEnvironment.getApplication());
    first.setKeepScreenOn(true);
    assertTrue(new ConfigStore(RuntimeEnvironment.getApplication()).isKeepScreenOn());
  }

  @Test
  public void localPreferenceIsExcludedFromHostSettingsState() {
    ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    config.setKeepScreenOn(true);
    assertFalse(config.hostSettingsState().containsKey(Constants.KEY_KEEP_SCREEN_ON));
  }
}
