package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** 依据历史代码建立的最小合成样本，不冒充真实用户升级数据。 */
public class LegacyBackupImporterTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  private byte[] bundle(Map<String, byte[]> files, int version, boolean corrupt) throws Exception {
    return bundle(files, version, corrupt, "full", Map.of());
  }

  private byte[] bundle(
      Map<String, byte[]> files,
      int version,
      boolean corrupt,
      String scope,
      Map<String, Object> graph)
      throws Exception {
    Map<String, Object> inventory = new LinkedHashMap<>();
    List<byte[]> entries = new ArrayList<>();
    Set<String> dirs = new TreeSet<>();
    for (String path : files.keySet()) {
      String parent = path;
      while (parent.contains("/")) {
        parent = parent.substring(0, parent.lastIndexOf('/'));
        dirs.add(parent);
      }
    }
    for (String dir : dirs) {
      inventory.put(dir, Map.of("directory", true));
      entries.add(LegacyTarReaderTest.tar(dir, '5', new byte[0]));
    }
    for (var row : files.entrySet()) {
      inventory.put(
          row.getKey(),
          Map.of(
              "size",
              (long) row.getValue().length,
              "sha256",
              BackupArchive.hex(BackupArchive.sha().digest(row.getValue()))));
      entries.add(LegacyTarReaderTest.tar(row.getKey(), '0', row.getValue()));
    }
    if (version > 0) {
      if (corrupt) inventory.put(".dsh/sessions/a", Map.of("size", 3L, "sha256", "0".repeat(64)));
      Map<String, Object> manifest = new LinkedHashMap<>();
      manifest.put("formatVersion", version);
      manifest.put("scope", scope);
      manifest.put("appVersion", "synthetic");
      if (version >= 3) manifest.put("inventory", inventory);
      if (version == 4) manifest.put("pluginDependencyGraph", graph);
      entries.add(
          LegacyTarReaderTest.tar(
              ".dsha-backup-manifest.json",
              '0',
              BackupJson.write(manifest, BackupLimits.MANIFEST)));
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (byte[] entry : entries) bytes.write(entry, 0, entry.length - 1024);
    bytes.write(new byte[1024]);
    return LegacyTarReaderTest.gzip(bytes.toByteArray());
  }

  @Test
  public void syntheticVersionsOneThroughFourConvertWithoutRuntime() throws Exception {
    for (int version = 0; version <= 4; version++) {
      File task = temporary.newFolder(),
          input = new File(task, "input"),
          output = new File(task, "converted");
      Files.write(
          input.toPath(),
          bundle(
              Map.of(".dsh/sessions/a", "old conversation".getBytes(StandardCharsets.UTF_8)),
              version,
              false));
      Map<String, Object> result =
          new LegacyBackupImporter(fs, task)
              .convert(input, output, "DSHA-backup-synthetic.tar.gz", new BackupControl(null));
      assertTrue(Boolean.TRUE.equals(result.get("legacyConfirmationRequired")));
      ByteArrayOutputStream payload = new ByteArrayOutputStream();
      try (InputStream in = new FileInputStream(output)) {
        BackupArchive.read(
            in,
            new BackupArchive.Visitor() {
              public OutputStream payload(int n, BackupArchive.Record record) {
                return record.kind.equals("FILE") ? payload : null;
              }
            },
            new BackupControl(null));
      }
      assertEquals("old conversation", payload.toString("UTF-8"));
    }
  }

  @Test
  public void missingInventoryAndUnknownScopesFailClosed() throws Exception {
    File task = temporary.newFolder(), input = new File(task, "input");
    Files.write(input.toPath(), bundle(Map.of(".dsh/sessions/a", new byte[3]), 4, true));
    assertThrows(
        IOException.class,
        () ->
            new LegacyBackupImporter(fs, task)
                .convert(
                    input,
                    new File(task, "bad"),
                    "DSHA-backup-test.tar.gz",
                    new BackupControl(null)));
    File other = temporary.newFolder(), raw = new File(other, "raw");
    Files.write(raw.toPath(), bundle(Map.of(".dsh/sessions/a", new byte[3]), 0, false));
    assertThrows(
        IOException.class,
        () ->
            new LegacyBackupImporter(fs, other)
                .convert(raw, new File(other, "out"), "unknown.tar.gz", new BackupControl(null)));
  }

  @Test
  public void legacyGzipWithBackupSuffixStillUsesContentInspection() throws Exception {
    File task = temporary.newFolder(),
        input = new File(task, "DSHA-backup-old.dshbak"),
        output = new File(task, "converted");
    Files.write(
        input.toPath(),
        bundle(Map.of(".dsh/sessions/a", "retained".getBytes(StandardCharsets.UTF_8)), 0, false));
    Map<String, Object> result =
        new LegacyBackupImporter(fs, task)
            .convert(input, output, input.getName(), new BackupControl(null));
    assertEquals("LEGACY_1", result.get("dataFormat"));
    assertEquals("LEGACY_PLAINTEXT", result.get("sensitivePolicy"));
    assertTrue(Boolean.TRUE.equals(result.get("legacyConfirmationRequired")));
    try (InputStream in = new FileInputStream(output)) {
      assertTrue(BackupArchive.hasMagic(in.readNBytes(8)));
    }
  }

  @Test
  public void partialScopeDoesNotExpandAndUnknownFullRecordsRemainForInspection() throws Exception {
    File task = temporary.newFolder(),
        input = new File(task, "old"),
        output = new File(task, "out");
    byte[] original =
        bundle(
            Map.of(".dsh/sessions/a", "old partial conversation".getBytes(StandardCharsets.UTF_8)),
            3,
            false,
            "sessions",
            Map.of());
    Files.write(input.toPath(), original);
    var manifest =
        new LegacyBackupImporter(fs, task)
            .convert(input, output, "DSHA-sessions-old.tar.gz", new BackupControl(null));
    for (Object root : (List<?>) manifest.get("roots"))
      assertEquals("sessions", ((Map<?, ?>) root).get("scope"));
    assertArrayEquals(original, Files.readAllBytes(input.toPath()));
    File bad = temporary.newFolder(), badInput = new File(bad, "input");
    Files.write(
        badInput.toPath(),
        bundle(
            Map.of(".dsh/settings.yaml", "keep: true".getBytes(StandardCharsets.UTF_8)),
            3,
            false,
            "sessions",
            Map.of()));
    assertEquals(
        "LEGACY_SCOPE_MISMATCH",
        assertThrows(
                IOException.class,
                () ->
                    new LegacyBackupImporter(fs, bad)
                        .convert(
                            badInput,
                            new File(bad, "out"),
                            "DSHA-sessions-old.tar.gz",
                            new BackupControl(null)))
            .getMessage());
    File unknown = temporary.newFolder(), unknownInput = new File(unknown, "input");
    Files.write(
        unknownInput.toPath(),
        bundle(
            Map.of(
                ".dsh/custom-user-record",
                "preserve unknown bytes".getBytes(StandardCharsets.UTF_8)),
            2,
            false));
    var retained =
        new LegacyBackupImporter(fs, unknown)
            .convert(
                unknownInput,
                new File(unknown, "out"),
                "DSHA-backup-old.tar.gz",
                new BackupControl(null));
    assertTrue(
        ((List<?>) retained.get("roots"))
            .stream().anyMatch(row -> "custom-user-record".equals(((Map<?, ?>) row).get("name"))));
  }

  @Test
  public void legacyV4UserPluginGraphAndExecutableOriginalsAreConvertedWithoutExecution()
      throws Exception {
    String node = "a".repeat(20);
    File task = temporary.newFolder(),
        input = new File(task, "old"),
        output = new File(task, "out");
    Map<String, byte[]> payload = new LinkedHashMap<>();
    payload.put(
        ".dsh/profiles/web/package.json",
        "{\"name\":\"dsh-profile-web\"}".getBytes(StandardCharsets.UTF_8));
    payload.put(
        ".dsha-plugin-src/.deps/" + node + "/package.json",
        "{\"name\":\"user-plugin\",\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8));
    payload.put(
        ".dsha-plugin-src/.deps/" + node + "/install.cjs",
        "throw Error('MUST_NOT_EXECUTE');".getBytes(StandardCharsets.UTF_8));
    byte[] original =
        bundle(
            payload,
            4,
            false,
            "plugins",
            Map.of(node, Map.of("name", "user-plugin", "links", Map.of())));
    Files.write(input.toPath(), original);
    var manifest =
        new LegacyBackupImporter(fs, task)
            .convert(input, output, "DSHA-plugins-old.tar.gz", new BackupControl(null));
    assertEquals(4L, ((Map<?, ?>) manifest.get("plugins")).get("legacyVersion"));
    assertTrue(
        ((List<?>) manifest.get("roots"))
            .stream()
                .anyMatch(row -> "plugin-package".equals(((Map<?, ?>) row).get("logicalKind"))));
    assertArrayEquals(original, Files.readAllBytes(input.toPath()));
  }
}
