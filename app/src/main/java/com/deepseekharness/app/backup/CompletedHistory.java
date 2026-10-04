package com.deepseekharness.app.backup;

import java.io.*;
import java.util.Map;

/** Shared v1 inode proof codec; each domain still validates its own terminal records. */
public final class CompletedHistory {
  private CompletedHistory() {}

  public static boolean matches(
      BackupFileSystem fs,
      File proof,
      String ownerField,
      String ownerKey,
      String historyKey,
      String errorPrefix)
      throws IOException {
    String type = fs.stat(proof).type;
    if (type.equals("MISSING")) return false;
    if (!type.equals("FILE")) throw new IOException(errorPrefix + "PROOF_TYPE");
    Map<String, Object> value = BackupJson.read(fs.small(proof, 4096), 4096);
    if (BackupJson.number(value, "version") != 1)
      throw new IOException(errorPrefix + "PROOF_FORMAT");
    return ownerKey.equals(BackupJson.string(value, ownerField))
        && historyKey.equals(BackupJson.string(value, "historyKey"));
  }

  public static void write(
      BackupFileSystem fs, File proof, String ownerField, String ownerKey, String historyKey)
      throws IOException {
    fs.atomic(
        proof.getParentFile(),
        proof.getName(),
        BackupJson.write(
            Map.of("version", 1L, ownerField, ownerKey, "historyKey", historyKey), 4096));
  }
}
