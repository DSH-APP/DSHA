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

/** 屏幕常亮与省电模式：默认关闭，两个本机偏好互不代写（启动归一由 MainActivity 调用纯策略落地）。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class ConfigStorePowerModeTest {
  private SharedPreferences preferences;

  @Before
  public void clearPowerModePreferences() {
    preferences =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    preferences.edit().remove(Constants.KEY_KEEP_SCREEN_ON).remove("runtime_eco_mode").commit();
  }

  @After
  public void restorePowerModePreferences() {
    preferences.edit().remove(Constants.KEY_KEEP_SCREEN_ON).remove("runtime_eco_mode").commit();
  }

  @Test
  public void bothSwitchesDefaultToOff() {
    ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    assertFalse(config.isKeepScreenOn());
    assertFalse(config.isEcoMode());
  }

  @Test
  public void writingOnePreferenceLeavesTheOtherUntouched() {
    ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    config.setKeepScreenOn(true);
    assertTrue(config.isKeepScreenOn());
    assertFalse(config.isEcoMode());

    ConfigStore reloaded = new ConfigStore(RuntimeEnvironment.getApplication());
    reloaded.setEcoMode(true);
    assertTrue(reloaded.isKeepScreenOn());
    assertTrue(reloaded.isEcoMode());

    reloaded.setKeepScreenOn(false);
    assertFalse(reloaded.isKeepScreenOn());
    assertTrue(reloaded.isEcoMode());
  }
}
