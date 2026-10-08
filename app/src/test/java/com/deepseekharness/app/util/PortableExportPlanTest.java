package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 便携包策略的锁定断言：白名单、凭据默认排除、剔除前缀、归档名。 */
public class PortableExportPlanTest {

  @Test
  public void whitelistAdmitsOnlyPortableUserData() {
    assertTrue(PortableExportPlan.selectable("sessions"));
    assertTrue(PortableExportPlan.selectable("sessions/--root-x--/id/session.v4.jsonl.zstd"));
    assertTrue(PortableExportPlan.selectable("storages/workspace.json"));
    assertTrue(PortableExportPlan.selectable("attachments/v1/objects/ab/cd"));
    assertTrue(PortableExportPlan.selectable("profiles/web/cordis.patch.yml"));
    assertTrue(PortableExportPlan.selectable("llm-deepseek/files-v3.json"));
  }

  @Test
  public void deviceBoundAndMachineStateNeverTravel() {
    String[] never = {
      "storages/session_projcache/sessions/x.json",
      "storages/session_projcache",
      "profiles/web/node_modules/dsh-web-mobile/index.js",
      "profiles/web/package.json",
      "profiles/web/pnpm-workspace.yaml",
      "node_modules/yaml/index.js",
      "cache/attachments/request-images/x",
      "sessions/../.bridge_token",
      "adb-shell.py",
      "credential-yaml-filter.cjs",
      "builtin-plugins.json",
      ".runtime-descriptor.json",
      ".bridge_headers",
      ".anonymous-user-id",
      "plugin-activations.json",
      "script-version",
    };
    for (String path : never) {
      assertFalse(path + " 不该进便携包", PortableExportPlan.selectable(path));
    }
  }

  @Test
  public void credentialsAreExcludedByDefaultAndMachineRecordsAlwaysPruned() {
    assertFalse(PortableExportPlan.credentialsIncludedByDefault());
    assertTrue(PortableExportPlan.mustPruneCredentialRecord("client-connection/browser-session"));
    assertTrue(PortableExportPlan.mustPruneCredentialRecord("client-connection/anything"));
    assertFalse(PortableExportPlan.mustPruneCredentialRecord("deepseek/api-key"));
    assertFalse(PortableExportPlan.mustPruneCredentialRecord(null));
  }

  @Test
  public void archiveNameIsSafeAndRecognizable() {
    String name = PortableExportPlan.archiveName("20261008-120000");
    assertEquals("DSHA-portable-20261008-120000.tar.gz", name);
    assertTrue(PortableExportPlan.looksLikePortableArchive(name));
    assertFalse(PortableExportPlan.looksLikePortableArchive("DSHA-backup-x.dshbak"));
    assertFalse(PortableExportPlan.looksLikePortableArchive("DSHA-portable-.tar.gz"));
    assertFalse(PortableExportPlan.looksLikePortableArchive(null));
    for (String hostile : new String[] {"../etc/passwd", "a/b", "", " ", "-lead", "x".repeat(200)}) {
      boolean rejected;
      try {
        PortableExportPlan.archiveName(hostile);
        rejected = false;
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      assertTrue("应拒绝标识 " + hostile, rejected);
    }
  }

  @Test
  public void readmeTellsTheUserWhatToDoAndStatesTheCredentialPolicy() {
    String withCredentials = PortableExportPlan.readme("0.2.0-rc.2", 26, true, "/root/Documents/ws");
    assertTrue(withCredentials.contains("dsha-portable-restore.mjs"));
    assertTrue(withCredentials.contains("client-connection/"));
    assertTrue(withCredentials.contains("/root/Documents/ws"));

    String without = PortableExportPlan.readme("0.2.0-rc.2", 3, false, "");
    assertTrue(without.contains("API Key"));
    assertTrue(without.contains("--rewrite-cwd"));
    assertTrue(PortableExportPlan.readme("0.2.0-rc.2", 1, false, "").contains("dsh web --no-open"));
  }
}
