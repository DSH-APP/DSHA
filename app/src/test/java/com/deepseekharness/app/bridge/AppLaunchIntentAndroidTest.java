package com.deepseekharness.app.bridge;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public final class AppLaunchIntentAndroidTest {
  private final Context context = RuntimeEnvironment.getApplication();

  private void install(String pkg, String activity, boolean exported) {
    ShadowPackageManager shadow = Shadow.extract(context.getPackageManager());
    PackageInfo info = new PackageInfo();
    info.packageName = pkg;
    shadow.installPackage(info);
    ComponentName component = new ComponentName(pkg, activity);
    ActivityInfo activityInfo = shadow.addActivityIfNotPresent(component);
    activityInfo.exported = exported;
    IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
    filter.addCategory(Intent.CATEGORY_LAUNCHER);
    shadow.addIntentFilterForActivity(component, filter);
  }

  @Test
  public void resolvesAnExportedLauncherOfTheSamePackageOnly() throws Exception {
    install("com.example.settings", "com.example.settings.Main", true);
    Intent intent = AppDeviceActions.launchIntent(context, "com.example.settings");
    assertNotNull(intent);
    assertEquals("com.example.settings", intent.getComponent().getPackageName());
  }

  @Test
  public void missingPackageHasNoEntry() throws Exception {
    // 非导出入口的过滤依赖真机 PackageManager；Robolectric 的 getLaunchIntentForPackage 不检查 exported。
    assertNull(AppDeviceActions.launchIntent(context, "com.example.absent"));
  }
}
