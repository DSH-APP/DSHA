package com.deepseekharness.app.core;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 有界应用事件记录；沿用安全文件层的完整发布与中断恢复。 */
public final class DiagnosticHistory {
  static final int MAX_BYTES = 32 * 1024;
  private static final String NAME = "diagnostic-events.txt";

  private DiagnosticHistory() {}

  public static String append(String previous, String line) {
    if (line.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      line = "[DIAGNOSTIC_EVENT_OVERSIZED]\n";
    byte[] value = (previous + line).getBytes(StandardCharsets.UTF_8);
    if (value.length <= MAX_BYTES) return new String(value, StandardCharsets.UTF_8);
    int start = value.length - MAX_BYTES;
    // Advance to a complete line, which also guarantees a UTF-8 code point boundary.
    while (start < value.length && value[start] != '\n') start++;
    if (start < value.length) start++;
    return new String(value, start, value.length - start, StandardCharsets.UTF_8);
  }

  public static String read(BackupFileSystem fs, File directory) throws IOException {
    File file = fs.child(directory, NAME);
    var node = fs.stat(file);
    if (node.type.equals("MISSING")) {
      file = fs.child(directory, NAME + ".previous");
      node = fs.stat(file);
      if (node.type.equals("MISSING")) return "";
    }
    if (!node.type.equals("FILE")) throw new IOException("DIAGNOSTIC_TARGET_TYPE");
    return new String(fs.small(file, 64 * 1024), StandardCharsets.UTF_8);
  }

  public static void write(BackupFileSystem fs, File directory, String line) throws IOException {
    String value = append(read(fs, directory), line);
    fs.atomic(directory, NAME, value.getBytes(StandardCharsets.UTF_8));
  }
}
