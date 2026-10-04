package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Cleanup boundary for derived, private plaintext after restore recovery is terminal. */
public final class RestoreStaging {
  private RestoreStaging() {}

  public static void clearSensitivePlaintext(BackupFileSystem fs, File operation)
      throws IOException {
    boolean switching = marker(fs, operation, "switching"),
        finalized = marker(fs, operation, "finalized"),
        rolledBack = marker(fs, operation, "rolled-back");
    if (switching && !finalized && !rolledBack)
      throw new IOException("RESTORE_TRANSACTION_UNRESOLVED");
    if (finalized && rolledBack) throw new IOException("RESTORE_TRANSACTION_AMBIGUOUS");
    File payload = fs.child(operation, "payload");
    String payloadType = fs.stat(payload).type;
    if (payloadType.equals("DIRECTORY")) fs.removeOwned(operation, "payload");
    else if (!payloadType.equals("MISSING")) throw new IOException("PLAINTEXT_STAGING_TYPE");
    for (String name : List.of("restore.dshdata", "snapshot.dshdata")) {
      File plain = fs.child(operation, name);
      String type = fs.stat(plain).type;
      if (type.equals("FILE")) fs.delete(plain);
      else if (!type.equals("MISSING")) throw new IOException("PLAINTEXT_STAGING_TYPE");
    }
  }

  private static boolean marker(BackupFileSystem fs, File operation, String name)
      throws IOException {
    return TransactionMarkers.read(fs, operation, name, "RESTORE_TRANSACTION_MARKER");
  }
}
