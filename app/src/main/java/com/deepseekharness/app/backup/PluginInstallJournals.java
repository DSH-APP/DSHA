package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 插件安装与卸载日志归入既有维护门禁；只读探测不执行旧树中的任何程序。 */
public final class PluginInstallJournals {
  public static final String DIRECTORY = "plugin-install-operations";
  private static final String COMPLETED = "completed", HISTORY_PROOF = ".completed-proof-v1.json";

  private PluginInstallJournals() {}

  public static List<File> pending(BackupFileSystem fs, File home) throws IOException {
    File directory = new File(home, DIRECTORY);
    verifyHistory(fs, home);
    if (fs.stat(directory).type.equals("MISSING")) return Collections.emptyList();
    if (!fs.stat(directory).type.equals("DIRECTORY"))
      throw new IOException("PLUGIN_JOURNAL_DIRECTORY");
    // 历史完整记录可长期保留；只把确实未提交的计划交给恢复入口。
    List<String> names = fs.list(directory);
    List<File> result = new ArrayList<>();
    for (String id : names) {
      if (id.equals(COMPLETED) || id.equals(HISTORY_PROOF)) continue;
      if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
        throw new IOException("PLUGIN_JOURNAL_ID");
      File entry = fs.child(directory, id);
      if (!fs.stat(entry).type.equals("DIRECTORY"))
        throw new IOException("PLUGIN_JOURNAL_DIRECTORY");
      if (!fs.stat(new File(directory, COMPLETED + "/" + id)).type.equals("MISSING"))
        throw new IOException("PLUGIN_JOURNAL_DUPLICATE");
      if (marker(fs, entry, "committed") || marker(fs, entry, "rolled-back")) continue;
      if (!fs.stat(new File(entry, "plan.json")).type.equals("MISSING")) result.add(entry);
    }
    return result;
  }

  private static void plan(BackupFileSystem fs, File entry, String id) throws IOException {
    Map<String, Object> value =
        BackupJson.read(
            fs.small(new File(entry, "plan.json"), BackupLimits.MANIFEST), BackupLimits.MANIFEST);
    long format = BackupJson.number(value, "format");
    if (!id.equals(BackupJson.string(value, "id")) || format < 1 || format > 3)
      throw new IOException("PLUGIN_JOURNAL_PLAN");
  }

  private static void verifyHistory(BackupFileSystem fs, File home) throws IOException {
    File directory = new File(home, DIRECTORY),
        history = new File(directory, COMPLETED),
        proof = new File(directory, HISTORY_PROOF);
    String type = fs.stat(history).type;
    if (type.equals("MISSING")) {
      if (!fs.stat(proof).type.equals("MISSING"))
        throw new IOException("PLUGIN_HISTORY_PROOF_ORPHAN");
      return;
    }
    if (!type.equals("DIRECTORY") || !fs.stat(directory).type.equals("DIRECTORY"))
      throw new IOException("PLUGIN_HISTORY_TYPE");
    String homeKey = fs.stat(home).key, historyKey = fs.stat(history).key;
    if (CompletedHistory.matches(fs, proof, "homeKey", homeKey, historyKey, "PLUGIN_HISTORY_"))
      return;
    for (String id : fs.list(history)) {
      if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
        throw new IOException("PLUGIN_HISTORY_ID");
      File entry = fs.child(history, id);
      if (!fs.stat(entry).type.equals("DIRECTORY")
          || marker(fs, entry, "committed") == marker(fs, entry, "rolled-back"))
        throw new IOException("PLUGIN_HISTORY_NOT_TERMINAL");
      plan(fs, entry, id);
      if (!fs.stat(new File(directory, id)).type.equals("MISSING"))
        throw new IOException("PLUGIN_JOURNAL_DUPLICATE");
    }
    CompletedHistory.write(fs, proof, "homeKey", homeKey, historyKey);
  }

  public static void archiveCompleted(BackupFileSystem fs, File home) throws IOException {
    File directory = new File(home, DIRECTORY), history = new File(directory, COMPLETED);
    verifyHistory(fs, home);
    if (fs.stat(directory).type.equals("MISSING")) return;
    for (String id : fs.list(directory)) {
      if (id.equals(COMPLETED) || id.equals(HISTORY_PROOF)) continue;
      if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
        throw new IOException("PLUGIN_JOURNAL_ID");
      File entry = fs.child(directory, id);
      if (!fs.stat(entry).type.equals("DIRECTORY"))
        throw new IOException("PLUGIN_JOURNAL_DIRECTORY");
      boolean committed = marker(fs, entry, "committed"),
          rolledBack = marker(fs, entry, "rolled-back");
      if (!committed && !rolledBack) continue;
      if (committed && rolledBack) throw new IOException("PLUGIN_JOURNAL_TERMINAL_CONFLICT");
      plan(fs, entry, id);
      if (fs.stat(history).type.equals("MISSING")) fs.directory(history);
      File target = fs.child(history, id);
      if (!fs.stat(target).type.equals("MISSING"))
        throw new IOException("PLUGIN_JOURNAL_DUPLICATE");
      BackupFileSystem.Node before = fs.stat(entry);
      fs.move(entry, target);
      fs.syncDirectory(directory);
      fs.syncDirectory(history);
      if (!before.key.equals(fs.stat(target).key))
        throw new IOException("PLUGIN_JOURNAL_ARCHIVE_IDENTITY");
      verifyHistory(fs, home);
    }
  }

  private static boolean marker(BackupFileSystem fs, File directory, String name)
      throws IOException {
    return TransactionMarkers.read(fs, directory, name, "PLUGIN_JOURNAL_MARKER");
  }

  public static boolean blocked(File files) {
    try {
      var fs = new AndroidBackupFileSystem();
      return !pending(fs, new UserDataLayout(fs, files.getCanonicalFile()).current()).isEmpty();
    } catch (IOException error) {
      return true;
    }
  }
}
