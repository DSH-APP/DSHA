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

/** 隐藏后台是本机任务可见性偏好，默认关闭且不进入主机备份设置。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class ConfigStoreHideFromRecentsTest {
  private SharedPreferences preferences;

  @Before
  public void clearHideFromRecentsPreference() {
    preferences =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    preferences.edit().remove(Constants.KEY_HIDE_FROM_RECENTS).commit();
    preferences.edit().remove(Constants.KEY_HIDE_RECENTS_STRICT).commit();
  }

  @After
  public void restoreHideFromRecentsPreference() {
    preferences.edit().remove(Constants.KEY_HIDE_FROM_RECENTS).commit();
    preferences.edit().remove(Constants.KEY_HIDE_RECENTS_STRICT).commit();
  }

  @Test
  public void missingPreferenceDefaultsToOff() {
    assertFalse(new ConfigStore(RuntimeEnvironment.getApplication()).isHideFromRecents());
  }

  @Test
  public void enabledPreferenceSurvivesAConfigStoreReload() {
    ConfigStore first = new ConfigStore(RuntimeEnvironment.getApplication());
    first.setHideFromRecents(true);
    assertTrue(new ConfigStore(RuntimeEnvironment.getApplication()).isHideFromRecents());
  }

  @Test
  public void localPreferenceIsExcludedFromHostSettingsState() {
    ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    config.setHideFromRecents(true);
    assertFalse(config.hostSettingsState().containsKey(Constants.KEY_HIDE_FROM_RECENTS));
  }

  @Test
  public void strictModeDefaultsOffAndSurvivesAConfigStoreReload() {
    assertFalse(new ConfigStore(RuntimeEnvironment.getApplication()).isHideRecentsStrict());
    new ConfigStore(RuntimeEnvironment.getApplication()).setHideRecentsStrict(true);
    assertTrue(new ConfigStore(RuntimeEnvironment.getApplication()).isHideRecentsStrict());
  }

  @Test
  public void strictModeIsExcludedFromHostSettingsState() {
    ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    config.setHideRecentsStrict(true);
    assertFalse(config.hostSettingsState().containsKey(Constants.KEY_HIDE_RECENTS_STRICT));
  }
}
