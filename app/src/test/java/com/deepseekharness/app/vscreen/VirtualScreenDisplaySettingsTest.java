package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.content.Context;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.VirtualScreenDisplayPreset;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 显示规格偏好：默认值、显式选择、坏值回退与白名单写入保护。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenDisplaySettingsTest {
  private Context context;

  @Before
  public void clearPreferences() {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
  }

  @Test
  public void unsetPreferenceReadsAsTheDefaultPreset() {
    assertEquals(
        VirtualScreenDisplayPreset.defaultPreset().id,
        VirtualScreenDisplaySettings.preset(context).id);
  }

  @Test
  public void explicitChoiceIsPersistedAcrossReads() {
    VirtualScreenDisplaySettings.setPreset(context, "compat-720");
    VirtualScreenDisplayPreset.Preset compat = VirtualScreenDisplayPreset.parse("compat-720");
    assertEquals(compat.id, VirtualScreenDisplaySettings.preset(context).id);
    assertEquals(720, VirtualScreenDisplaySettings.preset(context).width);
    assertEquals(1600, VirtualScreenDisplaySettings.preset(context).height);
    assertEquals(320, VirtualScreenDisplaySettings.preset(context).density);
  }

  @Test
  public void unknownStoredValuesFallBackToTheDefault() {
    context
        .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(VirtualScreenDisplayPreset.KEY_PRESET, "1440x2560@560")
        .commit();
    assertEquals(
        VirtualScreenDisplayPreset.defaultPreset().id,
        VirtualScreenDisplaySettings.preset(context).id);
  }

  @Test
  public void whitelistRejectsWritesSoABadIdCannotReplaceAValidChoice() {
    VirtualScreenDisplaySettings.setPreset(context, "compat-720");
    VirtualScreenDisplaySettings.setPreset(context, "qhd-1440");
    VirtualScreenDisplaySettings.setPreset(context, "");
    VirtualScreenDisplaySettings.setPreset(context, null);
    assertEquals(
        "compat-720",
        context
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getString(VirtualScreenDisplayPreset.KEY_PRESET, ""));
    assertEquals("compat-720", VirtualScreenDisplaySettings.preset(context).id);
  }
}
