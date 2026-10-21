package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

/** Standard tar/gzip transport around the unchanged, independently verified v5 container. */
public final class PortableBackupEnvelope {
  public static final String PAYLOAD = "portable.dshbak", README = "README.txt";
  private static final long CONTAINER_LIMIT = BackupLimits.BYTES + 256L * 1024 * 1024;
  private static final byte[] NOTE =
      ("DSHA application backup\n\n"
              + "Import this tar.gz file in DSHA's Backup and restore page.\n"
              + "portable.dshbak contains the original verified backup and its actual data scope.\n"
              + "If password protection was enabled, the original password is required.\n"
              + "Do not modify the payload. Keep your password separately.\n")
          .getBytes(StandardCharsets.UTF_8);

  private PortableBackupEnvelope() {}

  public static void writeUnencrypted(
      BackupFileSystem fs,
      File payload,
      String expectedSha,
      OutputStream target,
      BackupControl control)
      throws IOException {
    try (InputStream input = fs.read(payload, fs.stat(payload))) {
      byte[] header = new byte[8];
      new DataInputStream(input).readFully(header);
      if (!BackupArchive.hasMagic(header)) throw new IOException("PASSWORD_PROTECTED_EXPORT");
    }
    byte[] note =
        ("DSHA application backup\n\nImport this tar.gz file in DSHA's Backup and restore page.\n"
                + "This backup does not require a password.\nportable.dshbak contains verified data and its actual scope.\n"
                + "Do not modify the payload. Keep the backup in a trusted location.\n")
            .getBytes(StandardCharsets.UTF_8);
    write(fs, payload, expectedSha, target, control, note);
  }

  public static void write(
      BackupFileSystem fs,
      File payload,
      String expectedSha,
      OutputStream target,
      BackupControl control)
      throws IOException {
    write(fs, payload, expectedSha, target, control, NOTE);
  }

  private static void write(
      BackupFileSystem fs,
      File payload,
      String expectedSha,
      OutputStream target,
      BackupControl control,
      byte[] note)
      throws IOException {
    BackupFileSystem.Node node = fs.stat(payload);
    if (!node.type.equals("FILE") || node.size < 8 || node.size > CONTAINER_LIMIT)
      throw new IOException("ENVELOPE_PAYLOAD_SIZE");
    java.security.MessageDigest digest = BackupArchive.sha();
    try (GZIPOutputStream gzip = new GZIPOutputStream(target, 65536)) {
      gzip.write(header(PAYLOAD, node.size));
      long copied = 0;
      try (InputStream input = fs.read(payload, node)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = input.read(buffer)) != -1) {
          control.check();
          copied = BackupLimits.add(copied, n, node.size);
          gzip.write(buffer, 0, n);
          digest.update(buffer, 0, n);
          control.report("PACKAGING", 0, copied);
        }
      }
      if (copied != node.size || !BackupArchive.hex(digest.digest()).equals(expectedSha))
        throw new IOException("VERIFIED_COPY_CHANGED");
      padding(gzip, node.size);
      gzip.write(header(README, note.length));
      gzip.write(note);
      padding(gzip, note.length);
      gzip.write(new byte[1024]);
      control.check();
    }
  }

  /** Probe names only to route legacy input; accepted envelopes always consume and verify all bytes. */
  public static boolean unwrapIfPresent(
      BackupFileSystem fs, File input, File output, BackupControl control) throws IOException {
    final boolean[] recognized = {false}, payload = {false}, readme = {false};
    try (InputStream raw = fs.read(input, fs.stat(input))) {
      LegacyTarReader.readWithLimit(
          raw,
          (member, data) -> {
            if (!recognized[0] && !member.path.equals(PAYLOAD)) throw new NotEnvelope();
            recognized[0] = true;
            if (!member.type.equals("FILE") || !member.target.isEmpty())
              throw new IOException("ENVELOPE_MEMBER_TYPE");
            if (member.path.equals(PAYLOAD)) {
              if (payload[0] || member.size < 8 || member.size > CONTAINER_LIMIT)
                throw new IOException("ENVELOPE_PAYLOAD_SIZE");
              payload[0] = true;
              byte[] magic = new byte[8];
              new DataInputStream(data).readFully(magic);
              if (!Arrays.equals(magic, PortableBackupCrypto.MAGIC)
                  && !BackupArchive.hasMagic(magic))
                throw new IOException("ENVELOPE_PAYLOAD_FORMAT");
              try (OutputStream out = fs.create(output)) {
                out.write(magic);
                byte[] buffer = new byte[65536];
                int n;
                while ((n = data.read(buffer)) != -1) {
                  control.check();
                  out.write(buffer, 0, n);
                }
              }
            } else if (member.path.equals(README)) {
              if (readme[0] || member.size > 16384) throw new IOException("ENVELOPE_README");
              readme[0] = true;
            } else throw new IOException("ENVELOPE_EXTRA_MEMBER");
          },
          control,
          CONTAINER_LIMIT);
    } catch (NotEnvelope ordinaryLegacy) {
      return false;
    }
    if (!recognized[0]) return false;
    if (!payload[0]) throw new IOException("ENVELOPE_PAYLOAD_MISSING");
    if (!readme[0]) throw new IOException("ENVELOPE_README_MISSING");
    return true;
  }

  /** UI routing only: complete authentication/CRC/path checks still run in restore preflight. */
  public static boolean passwordProtected(InputStream source, BackupControl control)
      throws IOException {
    try (PushbackInputStream raw = new PushbackInputStream(source, 8)) {
      control.check();
      byte[] magic = new byte[8];
      new DataInputStream(raw).readFully(magic);
      raw.unread(magic);
      if (Arrays.equals(magic, PortableBackupCrypto.MAGIC)) return true;
      if (BackupArchive.hasMagic(magic)) return false;
      try {
        LegacyTarReader.readWithLimit(
            raw,
            (member, data) -> {
              if (!member.path.equals(PAYLOAD)) throw new Protection(false);
              if (!member.type.equals("FILE") || member.size < 8)
                throw new IOException("ENVELOPE_PAYLOAD_FORMAT");
              byte[] inner = new byte[8];
              new DataInputStream(data).readFully(inner);
              if (Arrays.equals(inner, PortableBackupCrypto.MAGIC)) throw new Protection(true);
              if (BackupArchive.hasMagic(inner)) throw new Protection(false);
              throw new IOException("ENVELOPE_PAYLOAD_FORMAT");
            },
            control,
            CONTAINER_LIMIT);
      } catch (Protection found) {
        return found.password;
      }
      return false;
    }
  }

  private static final class NotEnvelope extends IOException {}

  private static final class Protection extends IOException {
    final boolean password;

    Protection(boolean password) {
      this.password = password;
    }
  }

  private static void padding(OutputStream out, long size) throws IOException {
    out.write(new byte[(int) ((512 - size % 512) % 512)]);
  }

  private static byte[] header(String name, long size) throws IOException {
    byte[] header = new byte[512];
    byte[] path = name.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(path, 0, header, 0, path.length);
    number(header, 100, 8, 0600);
    number(header, 108, 8, 0);
    number(header, 116, 8, 0);
    number(header, 124, 12, size);
    number(header, 136, 12, 0);
    Arrays.fill(header, 148, 156, (byte) ' ');
    header[156] = '0';
    byte[] magic = "ustar\00000".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(magic, 0, header, 257, magic.length);
    long checksum = 0;
    for (byte value : header) checksum += value & 255;
    number(header, 148, 8, checksum);
    return header;
  }

  private static void number(byte[] header, int offset, int length, long value) throws IOException {
    String octal = Long.toOctalString(value);
    if (octal.length() < length) {
      Arrays.fill(header, offset, offset + length - 1, (byte) '0');
      byte[] bytes = octal.getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(bytes, 0, header, offset + length - 1 - bytes.length, bytes.length);
    } else {
      for (int index = offset + length - 1; index > offset; index--) {
        header[index] = (byte) value;
        value >>>= 8;
      }
      if (value != 0) throw new IOException("ENVELOPE_SIZE");
      header[offset] = (byte) 0x80;
    }
  }
}
