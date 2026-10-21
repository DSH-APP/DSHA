package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackupFileNamesTest {
  @Test
  public void applicationExportsUseBackupSuffixAndMime() {
    String name = BackupFileNames.portableName();
    assertTrue(name.matches("DSHA-data-v5-[a-f0-9-]{36}\\.tar\\.gz"));
    assertEquals("application/gzip", BackupFileNames.exportMimeType(name));
    assertEquals(
        BackupFileNames.MIME_TYPE,
        BackupFileNames.exportMimeType(name.toUpperCase(java.util.Locale.ROOT)));
  }

  @Test
  public void backupSuffixChangePreservesPartialScopeAndLegacyDiscovery() {
    for (int scope : BackupScope.ALL) {
      String name = BackupScope.archiveName(scope, "20261001-120000");
      assertTrue(name.endsWith(".tar.gz"));
      assertEquals(scope, BackupScope.fromFileName(name));
      assertEquals(scope == BackupScope.FULL, BackupFileNames.discoverable(name));
    }
    assertTrue(BackupFileNames.discoverable("DSHA-backup-2026.tar.gz"));
    assertTrue(BackupFileNames.discoverable("DSHA-backup-2026.tar (1).gz"));
    assertTrue(BackupFileNames.discoverable("DSHA-backup-2026 (2).dshbak"));
    assertTrue(
        BackupFileNames.discoverable("DSHA-data-v5-11111111-1111-1111-1111-111111111111.tar.gz"));
    assertTrue(
        BackupFileNames.discoverable("DSHA-data-v5-11111111-1111-1111-1111-111111111111.dshbak"));
    assertTrue(BackupFileNames.discoverable("DSHA-migration-old.tgz"));
    assertFalse(BackupFileNames.discoverable("other.tar.gz"));
    assertFalse(BackupFileNames.discoverable("DSHA-backup-2026.dshbak.tmp"));
    assertFalse(BackupFileNames.discoverable(null));
  }

  @Test
  public void actualCompressionExportsKeepTheirMimeAndUnknownFilesAreNotGzip() {
    assertEquals("application/gzip", BackupFileNames.exportMimeType("plugin.tar.gz"));
    assertEquals("application/gzip", BackupFileNames.exportMimeType("old.tgz"));
    assertEquals("application/x-tar", BackupFileNames.exportMimeType("old.tar"));
    assertEquals("application/zip", BackupFileNames.exportMimeType("plugins.zip"));
    assertEquals("text/plain", BackupFileNames.exportMimeType("diagnostic.txt"));
    assertEquals("application/octet-stream", BackupFileNames.exportMimeType("archive.bin"));
    assertEquals("image/png", BackupFileNames.exportMimeType("report.PNG"));
    assertEquals("application/json", BackupFileNames.exportMimeType("report.json"));
    assertEquals("image/jpeg", BackupFileNames.exportMimeType("photo.JPEG"));
    assertEquals("image/webp", BackupFileNames.exportMimeType("preview.webp"));
    assertEquals("application/pdf", BackupFileNames.exportMimeType("report.pdf"));
  }
}
