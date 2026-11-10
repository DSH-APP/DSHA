package com.deepseekharness.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.WindowManager;

import com.deepseekharness.app.core.ConfigStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 验证全局 Activity 回调按本机设置挂载或清除窗口常亮标志。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class ModernAndroidUiKeepScreenOnTest {
  public static final class Host extends Activity {}

  private Host activity;
  private ConfigStore config;

  @Before
  public void setUp() {
    activity = Robolectric.buildActivity(Host.class).setup().get();
    config = new ConfigStore(activity);
    config.setKeepScreenOn(false);
    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @After
  public void tearDown() {
    config.setKeepScreenOn(false);
    activity.finish();
  }

  @Test
  public void enabledSettingKeepsTheCurrentWindowAwake() {
    config.setKeepScreenOn(true);
    ModernAndroidUi.applyKeepScreenOn(activity);
    assertTrue(
        (activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            != 0);
  }

  @Test
  public void disabledSettingClearsTheCurrentWindowFlag() {
    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    ModernAndroidUi.applyKeepScreenOn(activity);
    assertFalse(
        (activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            != 0);
  }
}
