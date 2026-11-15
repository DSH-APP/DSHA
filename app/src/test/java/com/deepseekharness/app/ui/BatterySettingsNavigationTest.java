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
  public void opensThisApplicationsDetailsWithoutRequestingExemption() {
    DeviceGrantsFragment.openBatterySettings(context, attempts::add, failures::incrementAndGet);
    assertEquals(1, attempts.size());
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempts.get(0).getAction());
    assertEquals("package:" + context.getPackageName(), attempts.get(0).getDataString());
    assertEquals(0, failures.get());
    assertNoExemptionRequest();
  }

  @Test
  public void missingAppDetailsFallsBackToAdvancedPowerUsage() {
    DeviceGrantsFragment.openBatterySettings(
        context,
        intent -> {
          attempts.add(intent);
          if (attempts.size() == 1) throw new ActivityNotFoundException("fixture");
        },
        failures::incrementAndGet);
    assertEquals(2, attempts.size());
    assertEquals("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", attempts.get(1).getAction());
    assertEquals("package:" + context.getPackageName(), attempts.get(1).getDataString());
    assertEquals(0, failures.get());
    assertNoExemptionRequest();
  }

  @Test
  public void restrictedAppDetailsAlsoFallsBack() {
    DeviceGrantsFragment.openBatterySettings(
        context,
        intent -> {
          attempts.add(intent);
          if (attempts.size() == 1) throw new SecurityException("fixture");
        },
        failures::incrementAndGet);
    assertEquals(2, attempts.size());
    assertEquals("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", attempts.get(1).getAction());
    assertEquals(0, failures.get());
  }

  @Test
  public void reportsFailureOnlyAfterAllSystemEntriesRejectNavigation() {
    DeviceGrantsFragment.openBatterySettings(
        context,
        intent -> {
          attempts.add(intent);
          throw new ActivityNotFoundException("fixture");
        },
        failures::incrementAndGet);
    assertEquals(3, attempts.size());
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempts.get(0).getAction());
    assertEquals("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", attempts.get(1).getAction());
    assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, attempts.get(2).getAction());
    assertEquals(1, failures.get());
    assertNoExemptionRequest();
  }

  @Test
  public void fallbackUsesTheActualPackageIdentity() {
    Context renamed =
        new ContextWrapper(context) {
          @Override
          public String getPackageName() {
            return "io.github.sunsetrne.dsha.fixture";
          }
        };
    DeviceGrantsFragment.openBatterySettings(renamed, attempts::add, failures::incrementAndGet);
    assertEquals("package:io.github.sunsetrne.dsha.fixture", attempts.get(0).getDataString());
    assertEquals(0, failures.get());
  }

  private void assertNoExemptionRequest() {
    for (Intent intent : attempts)
      assertNotEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.getAction());
  }
}
