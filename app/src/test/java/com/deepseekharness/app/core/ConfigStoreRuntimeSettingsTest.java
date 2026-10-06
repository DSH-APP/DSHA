package com.deepseekharness.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.deepseekharness.app.util.Constants;
import java.util.Map;
import org.junit.Test;

public class ConfigStoreRuntimeSettingsTest {
  @Test
  public void missingSettingsUseTheSingleConfigStoreDefaults() {
    var snapshot = ConfigStore.runtimeSettingsFrom(Map.of());
    assertEquals("auto", snapshot.dnsMode);
    assertFalse(snapshot.proroot);
    assertTrue(snapshot.staticLoader);
    assertFalse(snapshot.disableProotSeccomp);
  }

  @Test
  public void anUnselectedColdInstallMayTryFastModeWithoutChangingTheRuntimeDefault() {
    assertTrue(ConfigStore.preferFastColdModeFrom(Map.of()));
    var values = Map.of("dns_mode", "ipv4", "ui_language", "en");
    assertTrue(ConfigStore.preferFastColdModeFrom(values));
    assertFalse(ConfigStore.runtimeSettingsFrom(values).proroot);
  }

  @Test
  public void explicitProotAndHistoricalSelectionRecordsPreventAutomaticFastMode() {
    for (Object value : java.util.List.of("proot", "proroot", "", false, 1))
      assertFalse(
          ConfigStore.preferFastColdModeFrom(Map.of(Constants.KEY_CONTAINER_RUNTIME, value)));
    for (String key :
        java.util.List.of("cold_runtime_root", "cold_runtime_packages", "cold_runtime_mode"))
      assertFalse(ConfigStore.preferFastColdModeFrom(Map.of(key, "")));
  }

  @Test
  public void oneMapImageSupportsOldScalarTypesAndNormalizesDns() {
    var snapshot =
        ConfigStore.runtimeSettingsFrom(
            Map.of(
                Constants.KEY_CONTAINER_RUNTIME,
                "proroot",
                Constants.KEY_WORKDIR,
                "/my-project",
                "proroot_static_loader",
                "false",
                "proot_disable_seccomp",
                1,
                "dns_mode",
                "ipv4"));
    assertEquals("ipv4", snapshot.dnsMode);
    assertTrue(snapshot.proroot);
    assertFalse(snapshot.staticLoader);
    assertTrue(snapshot.disableProotSeccomp);
  }

  @Test
  public void historicalEmptyWorkdirDoesNotBlockUnrelatedRuntimeSettings() {
    var history =
        Map.of(
            Constants.KEY_WORKDIR,
            "",
            "dns_mode",
            "ipv4",
            Constants.KEY_CONTAINER_RUNTIME,
            "proroot");
    var snapshot = ConfigStore.runtimeSettingsFrom(history);
    assertEquals("", history.get(Constants.KEY_WORKDIR));
    assertEquals("ipv4", snapshot.dnsMode);
    assertTrue(snapshot.proroot);
    assertTrue(snapshot.staticLoader);
    assertFalse(snapshot.disableProotSeccomp);
  }
}
