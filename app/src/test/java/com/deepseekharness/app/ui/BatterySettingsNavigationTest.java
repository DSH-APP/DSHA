package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Actual production navigation method and Android Intents, with only system launch controlled. */
@RunWith(BatterySettingsNavigationTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BatterySettingsNavigationTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  private Context context;
  private final List<Intent> attempts = new ArrayList<>();
  private final AtomicInteger failures = new AtomicInteger();

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
  }

  @Test
  public void opensGeneralBatterySettingsWithoutRequestingExemption() {
    DeviceGrantsFragment.openBatterySettings(context, attempts::add, failures::incrementAndGet);
    assertEquals(1, attempts.size());
    assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, attempts.get(0).getAction());
    assertNull(attempts.get(0).getData());
    assertEquals(0, failures.get());
  }

  @Test
  public void missingGeneralSettingsFallsBackToThisApplicationsDetails() {
    DeviceGrantsFragment.openBatterySettings(
        context,
        intent -> {
          attempts.add(intent);
          if (attempts.size() == 1) throw new ActivityNotFoundException("fixture");
        },
        failures::incrementAndGet);
    assertDetailsFallback(context.getPackageName());
    assertEquals(0, failures.get());
  }

  @Test
  public void restrictedGeneralSettingsAlsoFallsBack() {
    DeviceGrantsFragment.openBatterySettings(
        context,
        intent -> {
          attempts.add(intent);
          if (attempts.size() == 1) throw new SecurityException("fixture");
        },
        failures::incrementAndGet);
    assertDetailsFallback(context.getPackageName());
    assertEquals(0, failures.get());
  }

  @Test
  public void reportsFailureOnlyAfterBothSystemEntriesRejectNavigation() {
    DeviceGrantsFragment.openBatterySettings(
        context,
        intent -> {
          attempts.add(intent);
          if (attempts.size() == 1) throw new ActivityNotFoundException("fixture");
          throw new SecurityException("fixture");
        },
        failures::incrementAndGet);
    assertDetailsFallback(context.getPackageName());
    assertEquals(1, failures.get());
  }

  @Test
  public void fallbackUsesTheActualPackageIdentity() {
    Context renamed =
        new ContextWrapper(context) {
          @Override
          public String getPackageName() {
            return "dsh.android.client.fixture";
          }
        };
    DeviceGrantsFragment.openBatterySettings(
        renamed,
        intent -> {
          attempts.add(intent);
          if (attempts.size() == 1) throw new ActivityNotFoundException("fixture");
        },
        failures::incrementAndGet);
    assertDetailsFallback("dsh.android.client.fixture");
    assertEquals(0, failures.get());
  }

  private void assertDetailsFallback(String packageName) {
    assertEquals(2, attempts.size());
    assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, attempts.get(0).getAction());
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempts.get(1).getAction());
    assertEquals("package:" + packageName, attempts.get(1).getDataString());
    for (Intent intent : attempts)
      assertNotEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.getAction());
  }
}
