package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Historical id/newline/name/newline format; callers retain domain error codes and phase order. */
public final class TransactionMarkers {
  private TransactionMarkers() {}

  public static boolean read(BackupFileSystem fs, File directory, String name, String error)
      throws IOException {
    File file = fs.child(directory, name);
    String type = fs.stat(file).type;
    if (type.equals("MISSING")) return false;
    if (!type.equals("FILE")
        || !expected(directory, name)
            .equals(new String(fs.small(file, 256), StandardCharsets.UTF_8)))
      throw new IOException(error);
    return true;
  }

  public static void write(BackupFileSystem fs, File directory, String name, String error)
      throws IOException {
    if (read(fs, directory, name, error)) return;
    try (OutputStream output = fs.create(fs.child(directory, name))) {
      output.write(expected(directory, name).getBytes(StandardCharsets.UTF_8));
    }
    fs.syncDirectory(directory);
  }

  private static String expected(File directory, String name) {
    return directory.getName() + "\n" + name + "\n";
  }
}
