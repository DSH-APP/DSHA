package com.deepseekharness.app.backup;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PortablePreferenceProjectionTest {
  @Test
  public void deviceCredentialsAndAutomaticBackupsNeverEnterProjection() {
    var values = new LinkedHashMap<String, Object>();
    values.put("ui_language", "en");
    values.put("workdir", "/sdcard/Projects/existing");
    values.put("confirm_shell", true);
    for (String key :
        List.of(
            "api_key",
            "bridge_token",
            "shizuku_authorized",
            "root_enabled",
            "adb_paired",
            "sms_authorized",
            "auto_backup",
            "backup_launch_count",
            "welcomed",
            "environment_ready")) values.put(key, true);
    assertEquals(
        Map.of("ui_language", "en", "workdir", "/sdcard/Projects/existing", "confirm_shell", true),
        PortablePreferenceProjection.project(values));
  }

  @Test
  public void wrongPreferenceTypesCannotBreakLanguageInitialization() {
    assertEquals(
        Map.of("desktop_mode", false),
        PortablePreferenceProjection.project(
            Map.of(
                "ui_language",
                true,
                "ui_theme",
                2,
                "port",
                3080,
                "confirm_shell",
                "false",
                "desktop_mode",
                false)));
  }

  @Test
  public void localTokensDoNotBlockImportButNewUserPreferencesDo() {
    var portable = Map.of("projectionVersion", 1, "ui_language", "zh");
    assertTrue(
        PortablePreferenceProjection.mayImport(
            Map.of("bridge_token", "new local token"), portable));
    assertFalse(PortablePreferenceProjection.mayImport(Map.of("ui_language", "en"), portable));
    assertFalse(
        PortablePreferenceProjection.mayImport(Map.of(), Map.of("projectionVersion", "invalid")));
  }

  @Test
  public void nativeDeviceGrantPreferencesNeverEnterPortableProjection() {
    for (String key : List.of("clipboard_read_mode_v1", "sms_read", "dsha_device_grants")) {
      assertTrue(PortablePreferenceProjection.nativeOnly(key));
      assertFalse(PortablePreferenceProjection.transferable(key));
      assertEquals(
          Map.of("ui_language", "en"),
          PortablePreferenceProjection.project(Map.of(key, "allow", "ui_language", "en")));
      assertTrue(
          PortablePreferenceProjection.mayImport(
              Map.of(key, "allow"), Map.of("projectionVersion", 1)));
    }
  }

  @Test
  public void retiredNativePermissionChoicesCannotMigrateFromSystemBackup() {
    for (String value : List.of("read-only", "workspace-write", "danger-full-access")) {
      assertEquals(
          Map.of("ui_language", "en"),
          PortablePreferenceProjection.project(
              Map.of("permission_mode", value, "ui_language", "en")));
      assertFalse(PortablePreferenceProjection.transferable("permission_mode"));
      assertTrue(
          PortablePreferenceProjection.mayImport(
              Map.of("permission_mode", value), Map.of("projectionVersion", 1)));
    }
  }
}
