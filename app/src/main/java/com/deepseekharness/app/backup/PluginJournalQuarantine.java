package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 插件事务日志的「隔离」出口：只把 {@link PluginInstallJournals#problems} 列出的记录原样移入
 * dsh 数据根下的 {@link #DIRECTORY}，不删除、不改写、不重放。用户确认的是计划摘要；执行时 重新计算，摘要变了就拒绝，避免确认后目录变化而误移其他记录。
 *
 * <p>只服务插件域：插件记录最坏情况是某个插件半装需重装，会话与配置不受影响。宿主数据、
 * 运行时与环境重建事务可能正处于半应用状态，跳过其回滚会让个人数据不一致，因此不提供隔离。
 */
public final class PluginJournalQuarantine {
  public static final String DIRECTORY = "plugin-install-quarantine";
  static final String MANIFEST = "manifest.json", ENTRIES = "entries";

  private PluginJournalQuarantine() {}

  /** 计划摘要：路径与原因逐行排序后的 SHA-256；空计划返回空串。 */
  public static String digest(List<PluginInstallJournals.Problem> problems) {
    if (problems.isEmpty()) return "";
    List<String> rows = new ArrayList<>();
    for (var problem : problems) rows.add(problem.path + "\t" + problem.reason);
    rows.sort(null);
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] value = sha.digest(String.join("\n", rows).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(value.length * 2);
      for (byte b : value) hex.append(String.format("%02x", b & 0xff));
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** 执行结果：本批隔离目录与移入的记录数。 */
  public static final class Result {
    public final File batch;
    public final int moved;

    Result(File batch, int moved) {
      this.batch = batch;
      this.moved = moved;
    }
  }

  /**
   * 在维护屏障内执行。expectedDigest 必须与当前计划一致；清单先以 moving 状态写入再逐条移动，
   * 中断时已移动部分有记录可查，未移动部分下次计划会再次列出。
   */
  public static Result apply(BackupFileSystem fs, File home, String expectedDigest)
      throws IOException {
    List<PluginInstallJournals.Problem> problems = PluginInstallJournals.problems(fs, home);
    if (problems.isEmpty()) throw new IOException("PLUGIN_QUARANTINE_NOTHING");
    if (expectedDigest == null || !expectedDigest.equals(digest(problems)))
      throw new IOException("PLUGIN_QUARANTINE_PLAN_CHANGED");
    File root = fs.child(home, DIRECTORY);
    String rootType = fs.stat(root).type;
    if (rootType.equals("MISSING")) fs.directory(root);
    else if (!rootType.equals("DIRECTORY")) throw new IOException("PLUGIN_QUARANTINE_ROOT");
    String id = UUID.randomUUID().toString();
    File batch = fs.child(root, id);
    if (!fs.stat(batch).type.equals("MISSING")) throw new IOException("PLUGIN_QUARANTINE_BATCH");
    fs.directory(batch);
    File entries = fs.child(batch, ENTRIES);
    fs.directory(entries);
    fs.syncDirectory(root);

    List<Map<String, Object>> rows = new ArrayList<>();
    for (int index = 0; index < problems.size(); index++) {
      var problem = problems.get(index);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("original", problem.path);
      row.put("stored", ENTRIES + "/" + index);
      row.put("reason", problem.reason);
      row.put("moved", false);
      rows.add(row);
    }
    writeManifest(fs, batch, id, "moving", rows);

    int moved = 0;
    for (int index = 0; index < problems.size(); index++) {
      var problem = problems.get(index);
      File source = new File(home, problem.path);
      BackupFileSystem.Node before = fs.stat(source);
      // 前一条（例如重复事务的另一侧）移走后，本条可能已不在原位；保持清单如实记录。
      if (before.type.equals("MISSING")) continue;
      File target = fs.child(entries, String.valueOf(index));
      fs.move(source, target);
      fs.syncDirectory(source.getParentFile());
      fs.syncDirectory(entries);
      if (!before.key.equals(fs.stat(target).key))
        throw new IOException("PLUGIN_QUARANTINE_IDENTITY");
      rows.get(index).put("type", before.type);
      rows.get(index).put("moved", true);
      moved++;
      writeManifest(fs, batch, id, "moving", rows);
    }
    writeManifest(fs, batch, id, "complete", rows);
    return new Result(batch, moved);
  }

  private static void writeManifest(
      BackupFileSystem fs, File batch, String id, String status, List<Map<String, Object>> rows)
      throws IOException {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("format", 1L);
    manifest.put("id", id);
    manifest.put("status", status);
    manifest.put("domain", PluginInstallJournals.DIRECTORY);
    manifest.put("entries", rows);
    fs.atomic(batch, MANIFEST, BackupJson.write(manifest, BackupLimits.MANIFEST));
  }
}
