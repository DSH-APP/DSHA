package com.deepseekharness.app.backup;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Bounded standard tar/gzip diagnostics; records carry checksums and explicit capture limits. */
public final class DiagnosticExportArchive {
  private static final int MAX_ENTRIES = 32, MAX_ENTRY_BYTES = 256 * 1024, MAX_BYTES = 1024 * 1024;

  private DiagnosticExportArchive() {}

  public static void write(
      OutputStream output,
      Map<String, byte[]> content,
      Map<String, Object> status,
      BackupControl control)
      throws IOException {
    if (content == null || content.size() > MAX_ENTRIES)
      throw new IOException("DIAGNOSTIC_EXPORT_ENTRIES");
    Map<String, byte[]> selected = new LinkedHashMap<>();
    var records = new ArrayList<Map<String, Object>>();
    long total = 0;
    for (var entry : content.entrySet()) {
      control.check();
      String name = BackupLimits.path(entry.getKey());
      byte[] bytes = entry.getValue();
      if (name.isEmpty()
          || name.equals("manifest.json")
          || name.length() > 100
          || !name.matches("[A-Za-z0-9_./-]+")
          || bytes == null
          || bytes.length > MAX_ENTRY_BYTES) throw new IOException("DIAGNOSTIC_EXPORT_ENTRY");
      total = BackupLimits.add(total, bytes.length, MAX_BYTES);
      byte[] snapshot = bytes.clone();
      selected.put(name, snapshot);
      records.add(
          Map.of(
              "path",
              name,
              "bytes",
              (long) snapshot.length,
              "sha256",
              BackupArchive.hex(BackupArchive.sha().digest(snapshot))));
    }
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("schema", 1L);
    manifest.put("kind", "DSHA_DIAGNOSTICS");
    manifest.put("dataBackup", false);
    manifest.put("automaticUpload", false);
    manifest.put("capture", status);
    manifest.put("entries", records);
    byte[] metadata = BackupJson.write(manifest, MAX_ENTRY_BYTES);
    BackupLimits.add(total, metadata.length, MAX_BYTES);
    control.check();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output, 65536)) {
      for (var entry : selected.entrySet()) {
        control.check();
        member(gzip, entry.getKey(), entry.getValue());
      }
      member(gzip, "manifest.json", metadata);
      gzip.write(new byte[1024]);
      control.check();
    }
  }

  public static byte[] operationEvents(String text) throws java.io.IOException {
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    var pattern = java.util.regex.Pattern.compile("^([0-9-]+ [0-9:]+) \\[(.+?)] (.*)$");
    long sequence = 0;
    for (String line :
        com.deepseekharness.app.util.SensitiveData.redact(text == null ? "" : text)
            .split("\\r?\\n")) {
      if (line.isEmpty()) continue;
      var row = new java.util.LinkedHashMap<String, Object>();
      row.put("schema", 1L);
      row.put("source", "EXISTING_NATIVE_OPERATION_TAIL");
      row.put("capture", "TAIL");
      row.put("sequenceInExport", ++sequence);
      var matched = pattern.matcher(line);
      if (matched.matches()) {
        row.put("recordedTimeWithoutYearOrZone", matched.group(1));
        row.put("stage", matched.group(2));
        row.put("message", matched.group(3));
      } else {
        row.put("stage", "UNPARSED_EXISTING_LINE");
        row.put("message", line);
      }
      output.write(com.deepseekharness.app.backup.BackupJson.write(row, 65536));
      output.write('\n');
      if (output.size() > 256 * 1024)
        throw new java.io.IOException("DIAGNOSTIC_EXPORT_EVENT_LIMIT");
    }
    return output.toByteArray();
  }

  private static void member(OutputStream output, String name, byte[] bytes) throws IOException {
    byte[] header = new byte[512];
    byte[] path = name.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(path, 0, header, 0, path.length);
    number(header, 100, 8, 0600);
    number(header, 108, 8, 0);
    number(header, 116, 8, 0);
    number(header, 124, 12, bytes.length);
    number(header, 136, 12, 0);
    Arrays.fill(header, 148, 156, (byte) ' ');
    header[156] = '0';
    byte[] magic = "ustar\00000".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(magic, 0, header, 257, magic.length);
    long checksum = 0;
    for (byte value : header) checksum += value & 255;
    number(header, 148, 8, checksum);
    output.write(header);
    output.write(bytes);
    output.write(new byte[(512 - bytes.length % 512) % 512]);
  }

  private static void number(byte[] header, int offset, int length, long value) throws IOException {
    String octal = Long.toOctalString(value);
    if (octal.length() >= length) throw new IOException("DIAGNOSTIC_EXPORT_SIZE");
    Arrays.fill(header, offset, offset + length - 1, (byte) '0');
    byte[] bytes = octal.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(bytes, 0, header, offset + length - 1 - bytes.length, bytes.length);
  }
}
