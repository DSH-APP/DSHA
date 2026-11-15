package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.PluginJournalNames;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 插件安装与卸载日志归入既有维护门禁；只读探测不执行旧树中的任何程序。
 *
 * <p>完成证明是可再生缓存，存放在事务目录之外（{@link #HISTORY_PROOF}），其原子写残留不会混入
 * 被逐项扫描的事务目录。目录子项按 {@link PluginJournalNames} 分类：旧版证明残留与外来条目原样
 * 保留、不阻塞维护。
 */
public final class PluginInstallJournals {
  public static final String DIRECTORY = "plugin-install-operations",
      HISTORY_PROOF = "plugin-install-completed-v1.json";
  private static final String COMPLETED = PluginJournalNames.HISTORY;

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
      if (PluginJournalNames.kind(id) != PluginJournalNames.Kind.TRANSACTION) continue;
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
        proof = new File(home, HISTORY_PROOF);
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
      // 写方只把 UUID 事务移入历史；其他名字是外来条目，保留但不作为历史校验对象。
      if (PluginJournalNames.kind(id) != PluginJournalNames.Kind.TRANSACTION) continue;
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
      if (PluginJournalNames.kind(id) != PluginJournalNames.Kind.TRANSACTION) continue;
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

  /** 一条会让 {@link #pending} 失败的具体记录：相对 dsh 数据根的路径与稳定原因码。 */
  public static final class Problem {
    public final String path, reason;

    Problem(String path, String reason) {
      this.path = path;
      this.reason = reason;
    }
  }

  /**
   * 只读找出让本域无法读取的具体记录，不写证明、不移动任何文件。重复事务两侧都列出， 由用户确认后整体隔离，不猜测哪一份是真的。目录本身无法列出等更外层的故障照常抛出，
   * 交给诊断处理。
   */
  public static List<Problem> problems(BackupFileSystem fs, File home) throws IOException {
    Map<String, String> found = new LinkedHashMap<>();
    File directory = new File(home, DIRECTORY),
        history = new File(directory, COMPLETED),
        proof = new File(home, HISTORY_PROOF);
    String directoryType = fs.stat(directory).type;
    if (!directoryType.equals("MISSING") && !directoryType.equals("DIRECTORY")) {
      found.put(DIRECTORY, "PLUGIN_JOURNAL_DIRECTORY");
      if (!fs.stat(proof).type.equals("MISSING")) found.put(HISTORY_PROOF, "PLUGIN_HISTORY_PROOF");
      return list(found);
    }
    String historyType = directoryType.equals("MISSING") ? "MISSING" : fs.stat(history).type;
    boolean historyValid = historyType.equals("DIRECTORY");
    if (!historyType.equals("MISSING") && !historyValid)
      found.put(DIRECTORY + "/" + COMPLETED, "PLUGIN_HISTORY_TYPE");
    String proofType = fs.stat(proof).type;
    boolean proofMatched = false;
    if (!proofType.equals("MISSING")) {
      if (!historyValid) found.put(HISTORY_PROOF, "PLUGIN_HISTORY_PROOF_ORPHAN");
      else if (!proofType.equals("FILE")) found.put(HISTORY_PROOF, "PLUGIN_HISTORY_PROOF");
      else
        try {
          proofMatched =
              CompletedHistory.matches(
                  fs, proof, "homeKey", fs.stat(home).key, fs.stat(history).key, "PLUGIN_HISTORY_");
        } catch (IOException damaged) {
          // 证明只是可再生缓存，损坏时隔离后按完整校验重建。
          found.put(HISTORY_PROOF, "PLUGIN_HISTORY_PROOF");
        }
    }
    String previous = HISTORY_PROOF + ".previous";
    String previousType = fs.stat(new File(home, previous)).type;
    if (!previousType.equals("MISSING") && !previousType.equals("FILE"))
      found.put(previous, "PLUGIN_HISTORY_PROOF");
    Set<String> historyIds = new HashSet<>();
    if (historyValid)
      for (String id : fs.list(history)) {
        if (PluginJournalNames.kind(id) != PluginJournalNames.Kind.TRANSACTION) continue;
        historyIds.add(id);
        if (proofMatched) continue;
        String reason = historyProblem(fs, fs.child(history, id), id);
        if (reason != null) found.put(DIRECTORY + "/" + COMPLETED + "/" + id, reason);
      }
    if (directoryType.equals("DIRECTORY"))
      for (String id : fs.list(directory)) {
        if (PluginJournalNames.kind(id) != PluginJournalNames.Kind.TRANSACTION) continue;
        String reason;
        if (historyIds.contains(id)) {
          reason = "PLUGIN_JOURNAL_DUPLICATE";
          found.put(DIRECTORY + "/" + COMPLETED + "/" + id, reason);
        } else reason = activeProblem(fs, fs.child(directory, id), id);
        if (reason != null) found.put(DIRECTORY + "/" + id, reason);
      }
    return list(found);
  }

  private static String historyProblem(BackupFileSystem fs, File entry, String id)
      throws IOException {
    if (!fs.stat(entry).type.equals("DIRECTORY")) return "PLUGIN_HISTORY_NOT_TERMINAL";
    try {
      if (marker(fs, entry, "committed") == marker(fs, entry, "rolled-back"))
        return "PLUGIN_HISTORY_NOT_TERMINAL";
    } catch (IOException damaged) {
      return "PLUGIN_JOURNAL_MARKER";
    }
    try {
      plan(fs, entry, id);
    } catch (IOException damaged) {
      return "PLUGIN_JOURNAL_PLAN";
    }
    return null;
  }

  private static String activeProblem(BackupFileSystem fs, File entry, String id)
      throws IOException {
    if (!fs.stat(entry).type.equals("DIRECTORY")) return "PLUGIN_JOURNAL_DIRECTORY";
    boolean committed, rolledBack;
    try {
      committed = marker(fs, entry, "committed");
      rolledBack = marker(fs, entry, "rolled-back");
    } catch (IOException damaged) {
      return "PLUGIN_JOURNAL_MARKER";
    }
    if (committed && rolledBack) return "PLUGIN_JOURNAL_TERMINAL_CONFLICT";
    if (!committed && !rolledBack) return null; // 合法的未完成事务交给原有恢复入口，不隔离。
    try {
      plan(fs, entry, id);
    } catch (IOException damaged) {
      return "PLUGIN_JOURNAL_PLAN";
    }
    return null;
  }

  private static List<Problem> list(Map<String, String> found) {
    List<Problem> result = new ArrayList<>();
    for (var row : found.entrySet()) result.add(new Problem(row.getKey(), row.getValue()));
    return result;
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
