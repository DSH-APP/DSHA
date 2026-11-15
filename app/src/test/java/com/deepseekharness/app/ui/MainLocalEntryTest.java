package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/** 实际首次入口共用首次引导；检查期间不创建运行环境或加载旧业务页。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = MainLocalEntryTest.App.class,
    shadows = MainLocalEntryTest.NoRuntime.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class MainLocalEntryTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class NoRuntime {
    @Implementation
    protected static HarnessController get(Context context) {
      throw new AssertionError("首次设置门禁不能初始化 Ubuntu/HarnessController");
    }
  }

  @Before
  public void uninitializedPhone() {
    var config = new ConfigStore(RuntimeEnvironment.getApplication());
    config.setWelcomed(false);
    config.setCheckUpdate(false);
  }

  @Test
  public void launcherRequiresWelcomeWithoutCreatingTheRuntime() {
    var app = RuntimeEnvironment.getApplication();
    assertWelcome(
        new Intent(app, MainActivity.class)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER));
  }

  @Test
  public void explicitLocalDestinationsCannotSkipFirstSetup() {
    for (String destination :
        new String[] {
          "open_web", "open_plugins", "open_terminal", "open_install", FirstRunWebStart.EXTRA
        }) {
      assertWelcome(
          MainActivity.navigationIntent(RuntimeEnvironment.getApplication())
              .putExtra(destination, true));
    }
  }

  @Test
  public void retiredWorkspaceExtraCannotRestoreAnOptionalSetupHome() {
    assertWelcome(
        MainActivity.homeIntent(RuntimeEnvironment.getApplication())
            .putExtra("dsha.workspace.local", false));
  }

  private static void assertWelcome(Intent intent) {
    var host = Robolectric.buildActivity(MainActivity.class, intent).create();
    var main = host.get();
    var shadow =
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(main);
    Intent welcome = shadow.getNextStartedActivity();
    assertNotNull(welcome);
    assertEquals(OnboardingActivity.class.getName(), welcome.getComponent().getClassName());
    assertNull(shadow.getNextStartedActivity());
    assertTrue(main.isFinishing());
    assertTrue(main.getSupportFragmentManager().getFragments().isEmpty());
    host.destroy();
  }
}
