package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class CredentialPathsTest {
  @Test
  public void realMachineNamesAndDerivedAtomicFilesArePrivate() {
    for (String name :
        new String[] {
          ".bridge_token",
          ".bridge_headers",
          ".bridge_token.retained-fixture",
          ".bridge_token.previous",
          ".bridge_status.tmp-fixture",
          ".anonymous-user-id"
        }) {
      assertTrue(name, CredentialPaths.machine(name));
      assertTrue(BridgePathPolicy.denied("/root/work/" + name));
    }
  }

  @Test
  public void consumerScopesRemainDistinctAfterGeneratedTableAdoption() {
    assertTrue(CredentialPaths.backupMachine(".dsha-web.identity"));
    assertFalse(CredentialPaths.machine(".dsha-web.identity"));
    assertTrue(CredentialPaths.credentialName(".env"));
    assertFalse(CredentialPaths.backupMachine(".env"));
    assertTrue(com.deepseekharness.app.backup.DataRootPolicy.directData(".env"));
    assertEquals(
        "RETAINED_SESSION_TRASH",
        com.deepseekharness.app.backup.DataRootPolicy.exclusion(
            "sessions", ".sessions-trash/file"));
    assertEquals(
        "",
        com.deepseekharness.app.backup.DataRootPolicy.exclusion(
            "storages", ".sessions-trash/file"));
    assertEquals(
        "",
        com.deepseekharness.app.backup.DataRootPolicy.exclusion(
            "sessions", "project/.sessions-trash/file"));
  }

  @Test
  public void credentialBasenamesAndRuntimeNamePrefixDoNotUseDirectoryPrefixMatching() {
    for (String path :
        new String[] {
          "/root/.dsha-web.pid",
          "/root/.dsha-web.identity",
          "/root/.dsha-report.txt",
          "/root/work/.env",
          "/sdcard/project/.env.local",
          "/root/work/.credentials.yaml"
        }) assertTrue(path, BridgePathPolicy.denied(path));
    assertFalse(BridgePathPolicy.denied("/root/.dsh-backup-note.md"));
    assertFalse(BridgePathPolicy.denied("/root/report.md"));
  }
}
