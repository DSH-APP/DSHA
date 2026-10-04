package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.Assert.*;

public class PortableBackupEnvelopeTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();
  private final BackupControl control = new BackupControl(null);
  private final char[] password = "envelope backup password".toCharArray();

  private File save(String name, byte[] bytes) throws Exception {
    File file = new File(temporary.newFolder(), name);
    Files.write(file.toPath(), bytes);
    return file;
  }

  private byte[] wrap(byte[] inner) throws Exception {
    File file = save("portable.dshbak", inner);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PortableBackupEnvelope.write(
        fs, file, BackupArchive.hex(BackupArchive.sha().digest(inner)), out, control);
    return out.toByteArray();
  }

  private File unwrap(byte[] bytes) throws Exception {
    File input = save("backup.tar.gz", bytes), output = new File(input.getParentFile(), "payload");
    assertTrue(PortableBackupEnvelope.unwrapIfPresent(fs, input, output, control));
    return output;
  }

  private byte[] members(byte[]... entries) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] entry : entries) out.write(entry, 0, entry.length - 1024);
    out.write(new byte[1024]);
    return LegacyTarReaderTest.gzip(out.toByteArray());
  }

  private byte[] entry(String path, char type, byte[] body) throws Exception {
    return LegacyTarReaderTest.tar(path, type, body);
  }

  @Test
  public void standardGzipContainsRealTarAndPreservesUnencryptedContainer() throws Exception {
    byte[] inner = BackupArchiveTest.archive(), outer = wrap(inner);
    assertEquals(31, outer[0] & 255);
    assertEquals(139, outer[1] & 255);
    try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(outer))) {
      byte[] header = input.readNBytes(512);
      assertEquals("portable.dshbak", new String(header, 0, 15, StandardCharsets.US_ASCII));
      assertEquals("ustar", new String(header, 257, 5, StandardCharsets.US_ASCII));
      assertTrue(BackupArchive.hasMagic(input.readNBytes(8)));
    }
    File output = unwrap(outer);
    assertArrayEquals(inner, Files.readAllBytes(output.toPath()));
    try (InputStream in = new FileInputStream(output)) {
      assertEquals(2L, BackupArchive.read(in, null, control).get("entries"));
    }
  }

  @Test
  public void passwordAndPartialScopeArePreservedInsideTransport() throws Exception {
    ByteArrayOutputStream plain = new ByteArrayOutputStream();
    BackupArchive.Writer writer = new BackupArchive.Writer(plain, control);
    writer.add(BackupArchive.Record.of("sessions", "", "DIRECTORY", "sessions", 0, ""), null);
    Map<String, Object> summary = BackupArchiveTest.summary();
    summary.put("requestedScope", "sessions");
    summary.put("roots", List.of(Map.of("id", "sessions", "scope", "sessions")));
    writer.finish(summary);
    ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
    PortableBackupCrypto.encrypt(
        new ByteArrayInputStream(plain.toByteArray()), encrypted, password, control);
    File restored = unwrap(wrap(encrypted.toByteArray()));
    ByteArrayOutputStream authenticated = new ByteArrayOutputStream();
    try (InputStream in = new FileInputStream(restored)) {
      PortableBackupCrypto.decrypt(in, authenticated, password, control);
    }
    assertArrayEquals(plain.toByteArray(), authenticated.toByteArray());
    assertEquals(
        "sessions",
        BackupArchive.read(new ByteArrayInputStream(authenticated.toByteArray()), null, control)
            .get("requestedScope"));
    assertThrows(
        IOException.class,
        () -> {
          try (InputStream in = new FileInputStream(restored)) {
            PortableBackupCrypto.decrypt(
                in, new ByteArrayOutputStream(), "wrong password".toCharArray(), control);
          }
        });
  }

  @Test
  public void fullGzipCrcTruncationAndTrailingBytesMustPassBeforeUse() throws Exception {
    byte[] outer = wrap(BackupArchiveTest.archive()), altered = outer.clone();
    altered[altered.length - 8] ^= 1;
    for (byte[] bad :
        List.of(
            altered,
            Arrays.copyOf(outer, outer.length - 1),
            Arrays.copyOf(outer, outer.length + 1))) {
      File input = save("bad.tar.gz", bad), output = new File(input.getParentFile(), "payload");
      assertThrows(
          IOException.class,
          () -> PortableBackupEnvelope.unwrapIfPresent(fs, input, output, control));
    }
  }

  @Test
  public void duplicateExtraTraversalLinksAndLargeReadmeAreRejected() throws Exception {
    byte[] payload = entry("portable.dshbak", '0', BackupArchiveTest.archive()),
        readme = entry("README.txt", '0', new byte[0]);
    List<byte[]> bad =
        List.of(
            members(payload, payload, readme),
            members(payload, readme, entry("extra", '0', new byte[0])),
            members(payload, entry("../escape", '0', new byte[0])),
            members(entry("portable.dshbak", '2', new byte[0]), readme),
            members(payload, entry("README.txt", '0', new byte[16385])),
            members(payload),
            members(entry("portable.dshbak", '0', new byte[8]), readme));
    for (byte[] bytes : bad) {
      File input = save("malformed.tar.gz", bytes),
          output = new File(input.getParentFile(), "payload");
      assertThrows(
          IOException.class,
          () -> PortableBackupEnvelope.unwrapIfPresent(fs, input, output, control));
    }
  }

  @Test
  public void legacyArchivesAreRoutedWithoutCreatingAnEnvelopePayload() throws Exception {
    byte[] old = LegacyTarReaderTest.gzip(entry(".dsh/sessions/a", '0', new byte[3]));
    File input = save("DSHA-backup-old.tar.gz", old),
        output = new File(input.getParentFile(), "payload");
    assertFalse(PortableBackupEnvelope.unwrapIfPresent(fs, input, output, control));
    assertFalse(output.exists());
  }

  @Test
  public void changedSourceAndCancellationCannotPublishTransport() throws Exception {
    byte[] inner = BackupArchiveTest.archive();
    File input = save("inner", inner);
    assertThrows(
        IOException.class,
        () ->
            PortableBackupEnvelope.write(
                fs, input, "0".repeat(64), new ByteArrayOutputStream(), control));
    BackupControl cancelled = new BackupControl(null);
    cancelled.cancel();
    assertThrows(
        InterruptedIOException.class,
        () ->
            PortableBackupEnvelope.write(
                fs,
                input,
                BackupArchive.hex(BackupArchive.sha().digest(inner)),
                new ByteArrayOutputStream(),
                cancelled));
  }

  @Test
  public void envelopeTransportAllowanceDoesNotRelaxLegacyTarBudget() throws Exception {
    byte[] raw = entry("portable.dshbak", '0', new byte[0]);
    long size = BackupLimits.BYTES + 1;
    Arrays.fill(raw, 124, 136, (byte) 0);
    raw[124] = (byte) 0x80;
    for (int index = 135; index > 124; index--) {
      raw[index] = (byte) size;
      size >>>= 8;
    }
    Arrays.fill(raw, 148, 156, (byte) ' ');
    long checksum = 0;
    for (int index = 0; index < 512; index++) checksum += raw[index] & 255;
    LegacyTarReaderTest.put(raw, 148, String.format("%06o\0 ", checksum));
    assertEquals(
        "ARCHIVE_LIMIT",
        assertThrows(
                IOException.class,
                () ->
                    LegacyTarReader.read(
                        new ByteArrayInputStream(raw), (member, data) -> {}, control))
            .getMessage());
    assertEquals(
        "HEADER_ACCEPTED",
        assertThrows(
                IOException.class,
                () ->
                    LegacyTarReader.readWithLimit(
                        new ByteArrayInputStream(raw),
                        (member, data) -> {
                          throw new IOException("HEADER_ACCEPTED");
                        },
                        control,
                        BackupLimits.BYTES + 256L * 1024 * 1024))
            .getMessage());
  }
}
