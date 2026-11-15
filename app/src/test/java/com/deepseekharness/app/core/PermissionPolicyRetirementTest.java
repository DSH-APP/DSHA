package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.WebLaunchCommand;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 覆盖升级和历史设置事务只退休原生 DSH 档位，不清除其它配置或授权。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class PermissionPolicyRetirementTest {
  private Context context;
  private SharedPreferences prefs;
  private SharedPreferences device;

  @Before
  public void privatePreferences() {
    context = RuntimeEnvironment.getApplication();
    prefs = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    device = context.getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE);
    assertTrue(prefs.edit().clear().commit());
    assertTrue(device.edit().clear().commit());
  }

  @After
  public void removePrivateFixtures() {
    assertTrue(prefs.edit().clear().commit());
    assertTrue(device.edit().clear().commit());
  }

  @Test
  public void upgradeDropsEveryHistoricalNativeChoiceAndPreservesUnrelatedValues() {
    String screen = com.deepseekharness.app.util.ScreenConfirmationPolicy.manual().encode();
    assertTrue(device.edit().putString("screen_confirmation_v1", screen).commit());
    for (String value : new String[] {"read-only", "workspace-write", "danger-full-access"}) {
      assertTrue(
          prefs
              .edit()
              .putString(Constants.KEY_PERMISSION_MODE, value)
              .putString(Constants.KEY_WORKDIR, "/root/existing-project")
              .commit());
      ConfigStore config = new ConfigStore(context);
      assertFalse(prefs.contains(Constants.KEY_PERMISSION_MODE));
      assertEquals("/root/existing-project", config.getWorkdir());
      assertEquals(screen, device.getString("screen_confirmation_v1", ""));
      assertTrue(
          WebLaunchCommand.build(config.isConfirmShell(), "zh", "web", 0, 1)
              .contains("export DSH_PERMISSION_MODE='danger-full-access' && "));
    }
  }

  @Test
  public void userBackupIgnoresRetiredFieldAndDoesNotExportItAgain() throws Exception {
    ConfigStore config = new ConfigStore(context);
    for (String value : new String[] {"read-only", "workspace-write", "danger-full-access"}) {
      JSONObject old =
          new JSONObject()
              .put("permissionMode", value)
              .put("workdir", "/root/restored-project")
              .put("uiLanguage", "en");
      config.importBackupSettings(old);
      assertFalse(prefs.contains(Constants.KEY_PERMISSION_MODE));
      assertFalse(
          config.prepareHostSettings(old, false).containsKey(Constants.KEY_PERMISSION_MODE));
      assertFalse(config.exportBackupSettings().has("permissionMode"));
      assertFalse(config.exportPortableSettings(false).has("permissionMode"));
      assertEquals("/root/restored-project", config.getWorkdir());
      assertEquals("en", config.getUiLanguagePreference());
    }
  }

  @Test
  public void interruptedOldHostTransactionStillRestoresOtherSettings() throws Exception {
    ConfigStore config = new ConfigStore(context);
    config.applyHostSettings(
        Map.of(Constants.KEY_PERMISSION_MODE, "read-only", Constants.KEY_WORKDIR, "/root/old"));
    assertFalse(prefs.contains(Constants.KEY_PERMISSION_MODE));
    assertEquals("/root/old", config.getWorkdir());
    assertFalse(config.hostSettingsState().containsKey(Constants.KEY_PERMISSION_MODE));
    assertTrue(
        prefs
            .edit()
            .putString(Constants.KEY_PERMISSION_MODE, "workspace-write")
            .putString(
                "backup_restore_previous_settings",
                new JSONObject()
                    .put(Constants.KEY_PERMISSION_MODE, "read-only")
                    .put(Constants.KEY_WORKDIR, "/root/before-restore")
                    .toString())
            .commit());
    config.finishRestoreSettings(true);
    assertFalse(prefs.contains(Constants.KEY_PERMISSION_MODE));
    assertFalse(prefs.contains("backup_restore_previous_settings"));
    assertEquals("/root/before-restore", config.getWorkdir());
  }
}
