package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 启动只读取固定数量的小日志；不解析用户项目、不运行任何容器命令。 */
public final class HostPendingTransactions {
  private HostPendingTransactions() {}

  public static List<File> pending(BackupFileSystem fs, File files) throws IOException {
    HostOperationArchive.verifyCompleted(fs, files);
    File root = HostOperationArchive.root(files);
    if (fs.stat(root).type.equals("MISSING")) return Collections.emptyList();
    List<File> result = new ArrayList<>();
    File completed = HostOperationArchive.completedRoot(files);
    for (String name : HostOperationArchive.activeEntries(fs, root)) {
      if (!name.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
        throw new IOException("OPERATION_ID");
      if (!fs.stat(new File(completed, name)).type.equals("MISSING"))
        throw new IOException("OPERATION_DUPLICATE");
      File directory = fs.child(root, name);
      if (!fs.stat(directory).type.equals("DIRECTORY"))
        throw new IOException("OPERATION_DIRECTORY");
      boolean switching = marker(fs, directory, "switching"),
          finalized = marker(fs, directory, "finalized"),
          rolledBack = marker(fs, directory, "rolled-back");
      if (switching && !finalized && !rolledBack) {
        if (!fs.stat(new File(directory, "plan.json")).type.equals("FILE"))
          throw new IOException("TRANSACTION_PLAN_MISSING");
        result.add(directory);
      }
    }
    return result;
  }

  private static boolean marker(BackupFileSystem fs, File directory, String name)
      throws IOException {
    return TransactionMarkers.read(fs, directory, name, "TRANSACTION_MARKER");
  }

  public static boolean blocked(File files) {
    try {
      return !pending(new AndroidBackupFileSystem(), files.getCanonicalFile()).isEmpty();
    } catch (IOException error) {
      return true;
    }
  }
}
