package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class PasswordFreeBackupExportTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();
  private final BackupControl control = new BackupControl(null);
  private final char[] originalPassword = "old backup original password".toCharArray();

  private byte[] archive(String integrity) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BackupArchive.Writer writer = new BackupArchive.Writer(out, control);
    byte[] content = "保留的对话原件\n".getBytes(StandardCharsets.UTF_8);
    writer.add(BackupArchive.Record.of("sessions", "", "DIRECTORY", "sessions", 0, ""), null);
    writer.add(
        BackupArchive.Record.of(
            "sessions",
            "a",
            "FILE",
            "sessions",
            content.length,
            BackupArchive.hex(BackupArchive.sha().digest(content))),
        new ByteArrayInputStream(content));
    Map<String, Object> metadata = BackupArchiveTest.summary();
    metadata.put("roots", List.of(Map.of("id", "sessions", "scope", "sessions")));
    metadata.put("requestedScope", "sessions");
    metadata.put("sensitivePolicy", "PASSWORD_ENCRYPTED");
    metadata.put("integrity", integrity);
    metadata.put("plugins", Map.of("complete", false, "notice", "original warning"));
    writer.finish(metadata);
    return out.toByteArray();
  }

  private VerifiedBackupCopy fixture(boolean encrypted, String quality, boolean automatic)
      throws Exception {
    File operations = temporary.newFolder(),
        operation = new File(operations, UUID.randomUUID().toString());
    fs.directory(operation);
    File artifact = new File(operation, "portable.dshbak");
    byte[] plain = archive(quality);
    try (OutputStream out = fs.create(artifact)) {
      if (encrypted)
        PortableBackupCrypto.encrypt(
            new ByteArrayInputStream(plain), out, originalPassword, control);
      else out.write(plain);
    }
    Map<String, Object> metadata;
    try (InputStream in = new ByteArrayInputStream(plain)) {
      metadata = new LinkedHashMap<>(BackupArchive.read(in, null, control));
    }
    try (InputStream in = fs.read(artifact, fs.stat(artifact))) {
      metadata.put("encryptedSha256", BackupArchive.digest(in, control));
    }
    metadata.put("encryptedBytes", artifact.length());
    metadata.put("automatic", automatic);
    Files.write(
        new File(operation, "verified.json").toPath(),
        BackupJson.write(metadata, BackupLimits.MANIFEST));
    return VerifiedBackupCopy.inspect(fs, operations, operation.getName());
  }

  @Test
  public void existingPlainCopyExportsWithoutAnyPasswordAndPreservesFacts() throws Exception {
    var source = fixture(false, "PARTIAL", false);
    byte[] original = Files.readAllBytes(source.artifact.toPath());
    var export = PasswordFreeBackupExport.prepare(fs, source, temporary.newFolder(), null, control);
    assertArrayEquals(original, Files.readAllBytes(source.artifact.toPath()));
    assertEquals("UNENCRYPTED", export.manifest.get("sensitivePolicy"));
    assertEquals("sessions", export.manifest.get("requestedScope"));
    assertEquals("PARTIAL", export.manifest.get("integrity"));
    assertEquals("PARTIAL_RESCUE", source.result(true));
    assertEquals(
        Map.of("complete", false, "notice", "original warning"), export.manifest.get("plugins"));
    ByteArrayOutputStream contents = new ByteArrayOutputStream();
    try (InputStream in = fs.read(export.artifact, fs.stat(export.artifact))) {
      BackupArchive.read(
          in,
          new BackupArchive.Visitor() {
            public OutputStream payload(int ordinal, BackupArchive.Record record) {
              return record.kind.equals("FILE") ? contents : null;
            }
          },
          control);
    }
    assertEquals("保留的对话原件\n", contents.toString("UTF-8"));
  }

  @Test
  public void oldManualEncryptionIsAuthenticatedAndOriginalRetained() throws Exception {
    var source = fixture(true, "BEST_EFFORT", false);
    byte[] original = Files.readAllBytes(source.artifact.toPath());
    File operation = temporary.newFolder();
    var export = PasswordFreeBackupExport.prepare(fs, source, operation, originalPassword, control);
    assertArrayEquals(original, Files.readAllBytes(source.artifact.toPath()));
    assertTrue(source.passwordProtected(fs));
    try (InputStream in = fs.read(export.artifact, fs.stat(export.artifact))) {
      assertTrue(BackupArchive.hasMagic(in.readNBytes(8)));
    }
    assertEquals("UNENCRYPTED", export.manifest.get("sensitivePolicy"));
    assertEquals("BEST_EFFORT", export.manifest.get("integrity"));
    assertEquals("BEST_EFFORT_RESCUE", source.result(true));
    assertFalse(new File(operation, "reexport-authenticated.dshdata").exists());
  }

  @Test
  public void automaticCopyWithAvailableHostSecretExportsGenuinelyUnprotectedTar()
      throws Exception {
    var source = fixture(true, "QUIESCENT", true);
    var export =
        PasswordFreeBackupExport.prepare(
            fs, source, temporary.newFolder(), originalPassword, control);
    ByteArrayOutputStream outer = new ByteArrayOutputStream();
    PortableBackupEnvelope.writeUnencrypted(fs, export.artifact, export.sha256, outer, control);
    assertFalse(
        PortableBackupEnvelope.passwordProtected(
            new ByteArrayInputStream(outer.toByteArray()), control));
    File archive = new File(temporary.newFolder(), "backup.tar.gz"),
        payload = new File(archive.getParentFile(), "payload");
    Files.write(archive.toPath(), outer.toByteArray());
    assertTrue(PortableBackupEnvelope.unwrapIfPresent(fs, archive, payload, control));
    try (InputStream in = new FileInputStream(payload)) {
      assertEquals("UNENCRYPTED", BackupArchive.read(in, null, control).get("sensitivePolicy"));
    }
    assertEquals("DATA_SAVED_PLUGIN_WARNINGS", source.result(true));
    assertTrue(source.artifact.exists());
  }

  @Test
  public void missingWrongPasswordAndSourceTamperingCannotPublishPlaintext() throws Exception {
    var source = fixture(true, "QUIESCENT", false);
    byte[] original = Files.readAllBytes(source.artifact.toPath());
    File missing = temporary.newFolder();
    assertEquals(
        "ORIGINAL_BACKUP_PASSWORD_REQUIRED",
        assertThrows(
                IOException.class,
                () -> PasswordFreeBackupExport.prepare(fs, source, missing, null, control))
            .getMessage());
    assertFalse(new File(missing, "reexport-plain.dshbak").exists());
    File wrong = temporary.newFolder();
    assertThrows(
        IOException.class,
        () ->
            PasswordFreeBackupExport.prepare(
                fs, source, wrong, "wrong original password".toCharArray(), control));
    assertFalse(new File(wrong, "reexport-plain.dshbak").exists());
    assertFalse(new File(wrong, "reexport-authenticated.dshdata").exists());
    assertArrayEquals(original, Files.readAllBytes(source.artifact.toPath()));
    original[original.length - 1] ^= 1;
    Files.write(source.artifact.toPath(), original);
    File changed = temporary.newFolder();
    assertThrows(
        IOException.class,
        () -> PasswordFreeBackupExport.prepare(fs, source, changed, originalPassword, control));
    assertFalse(new File(changed, "reexport-plain.dshbak").exists());
  }

  @Test
  public void productionEnvelopeWriterRejectsEncryptedPayloadInsteadOfRenamingIt()
      throws Exception {
    var source = fixture(true, "QUIESCENT", false);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertEquals(
        "PASSWORD_PROTECTED_EXPORT",
        assertThrows(
                IOException.class,
                () ->
                    PortableBackupEnvelope.writeUnencrypted(
                        fs, source.artifact, source.sha256, output, control))
            .getMessage());
    assertEquals(0, output.size());
  }

  @Test
  public void protectionProbeRoutesOldInputsButNeverReplacesFullPreflight() throws Exception {
    var plain = fixture(false, "QUIESCENT", false);
    var encrypted = fixture(true, "QUIESCENT", false);
    try (InputStream in = fs.read(plain.artifact, fs.stat(plain.artifact))) {
      assertFalse(PortableBackupEnvelope.passwordProtected(in, control));
    }
    try (InputStream in = fs.read(encrypted.artifact, fs.stat(encrypted.artifact))) {
      assertTrue(PortableBackupEnvelope.passwordProtected(in, control));
    }
    ByteArrayOutputStream outer = new ByteArrayOutputStream();
    PortableBackupEnvelope.write(fs, plain.artifact, plain.sha256, outer, control);
    byte[] corrupt = outer.toByteArray();
    corrupt[corrupt.length - 8] ^= 1;
    assertFalse(
        PortableBackupEnvelope.passwordProtected(new ByteArrayInputStream(corrupt), control));
    File archive = new File(temporary.newFolder(), "corrupt.tar.gz"),
        payload = new File(archive.getParentFile(), "payload");
    Files.write(archive.toPath(), corrupt);
    assertThrows(
        IOException.class,
        () -> PortableBackupEnvelope.unwrapIfPresent(fs, archive, payload, control));
    outer.reset();
    PortableBackupEnvelope.write(fs, encrypted.artifact, encrypted.sha256, outer, control);
    assertTrue(
        PortableBackupEnvelope.passwordProtected(
            new ByteArrayInputStream(outer.toByteArray()), control));
  }

  @Test
  public void malformedPlainContainerCannotBecomeAValidUnprotectedCopy() throws Exception {
    byte[] original = archive("QUIESCENT");
    original[original.length - 1] ^= 1;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThrows(
        IOException.class,
        () -> BackupArchive.copyUnencrypted(new ByteArrayInputStream(original), output, control));
    assertThrows(
        IOException.class,
        () -> BackupArchive.read(new ByteArrayInputStream(output.toByteArray()), null, control));
  }
}
