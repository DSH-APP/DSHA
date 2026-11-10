package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 每种让 pending 失败的记录都能被识别并隔离；隔离后门禁可读、原件逐字节保留。 */
public class PluginJournalQuarantineTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  private File home() throws IOException {
    File home = temp.newFolder("dsh");
    Files.createDirectories(
        new File(home, PluginInstallJournals.DIRECTORY + "/completed").toPath());
    return home;
  }

  private File entry(File parent, String id, boolean terminal) throws IOException {
    File entry = new File(parent, id);
    Files.createDirectories(entry.toPath());
    Files.write(
        new File(entry, "plan.json").toPath(),
        BackupJson.write(Map.of("format", 2L, "id", id), 4096));
    if (terminal) Files.writeString(new File(entry, "committed").toPath(), id + "\ncommitted\n");
    return entry;
  }

  private File ops(File home) {
    return new File(home, PluginInstallJournals.DIRECTORY);
  }

  private List<String> reasons(File home) throws IOException {
    List<String> result = new ArrayList<>();
    for (var p : PluginInstallJournals.problems(fs, home)) result.add(p.path + "=" + p.reason);
    return result;
  }

  private interface Io {
    void run() throws IOException;
  }

  private static boolean fails(Io action) {
    try {
      action.run();
      return false;
    } catch (IOException expected) {
      return true;
    }
  }

  /** 门禁读不了，或已提交记录无法归档（Python 侧同样会拒绝新插件操作）都算坏记录。 */
  private void assertQuarantineRestoresGate(File home) throws IOException {
    assertTrue(
        fails(() -> PluginInstallJournals.pending(fs, home))
            || fails(() -> PluginInstallJournals.archiveCompleted(fs, home)));
    var problems = PluginInstallJournals.problems(fs, home);
    assertFalse(problems.isEmpty());
    var result = PluginJournalQuarantine.apply(fs, home, PluginJournalQuarantine.digest(problems));
    assertEquals(problems.size(), result.moved);
    assertTrue(PluginInstallJournals.problems(fs, home).isEmpty());
    PluginInstallJournals.pending(fs, home); // 不再抛出
    PluginInstallJournals.archiveCompleted(fs, home);
    Map<String, Object> manifest =
        BackupJson.read(
            Files.readAllBytes(new File(result.batch, PluginJournalQuarantine.MANIFEST).toPath()),
            BackupLimits.MANIFEST);
    assertEquals("complete", manifest.get("status"));
  }

  @Test
  public void healthyJournalsHaveNoProblemsAndNothingToIsolate() throws Exception {
    File home = home();
    entry(new File(ops(home), "completed"), UUID.randomUUID().toString(), true);
    entry(ops(home), UUID.randomUUID().toString(), false); // 合法未完成事务交给原有恢复入口
    assertTrue(PluginInstallJournals.problems(fs, home).isEmpty());
    assertEquals("", PluginJournalQuarantine.digest(List.of()));
    IOException none =
        assertThrows(IOException.class, () -> PluginJournalQuarantine.apply(fs, home, "anything"));
    assertEquals("PLUGIN_QUARANTINE_NOTHING", none.getMessage());
  }

  @Test
  public void damagedMarkerIsIsolatedAndOriginalBytesKept() throws Exception {
    File home = home();
    String id = UUID.randomUUID().toString();
    File bad = entry(ops(home), id, false);
    Files.writeString(new File(bad, "committed").toPath(), "untrusted");
    Files.writeString(new File(bad, "old-source.js").toPath(), "only copy of the old plugin");
    assertEquals(
        List.of(PluginInstallJournals.DIRECTORY + "/" + id + "=PLUGIN_JOURNAL_MARKER"),
        reasons(home));
    var problems = PluginInstallJournals.problems(fs, home);
    var result = PluginJournalQuarantine.apply(fs, home, PluginJournalQuarantine.digest(problems));
    assertFalse(bad.exists());
    assertEquals(
        "only copy of the old plugin",
        Files.readString(new File(result.batch, "entries/0/old-source.js").toPath()));
    assertTrue(PluginInstallJournals.pending(fs, home).isEmpty());
  }

  @Test
  public void everyPendingFailureKindIsIsolatable() throws Exception {
    // 非目录事务条目
    File home = home();
    Files.writeString(new File(ops(home), UUID.randomUUID().toString()).toPath(), "file");
    assertQuarantineRestoresGate(home);

    // 已提交但计划无效
    home = temp.newFolder("plan");
    Files.createDirectories(
        new File(home, PluginInstallJournals.DIRECTORY + "/completed").toPath());
    String id = UUID.randomUUID().toString();
    File noPlan = new File(ops(home), id);
    Files.createDirectories(noPlan.toPath());
    Files.writeString(new File(noPlan, "committed").toPath(), id + "\ncommitted\n");
    assertQuarantineRestoresGate(home);

    // 终态冲突
    home = temp.newFolder("conflict");
    Files.createDirectories(
        new File(home, PluginInstallJournals.DIRECTORY + "/completed").toPath());
    id = UUID.randomUUID().toString();
    File both = entry(ops(home), id, true);
    Files.writeString(new File(both, "rolled-back").toPath(), id + "\nrolled-back\n");
    assertQuarantineRestoresGate(home);

    // 历史条目未到终态
    home = temp.newFolder("history");
    Files.createDirectories(
        new File(home, PluginInstallJournals.DIRECTORY + "/completed").toPath());
    entry(new File(ops(home), "completed"), UUID.randomUUID().toString(), false);
    assertQuarantineRestoresGate(home);

    // 历史目录类型异常 + 孤立证明
    home = temp.newFolder("history-type");
    Files.createDirectories(ops(home).toPath());
    Files.writeString(new File(ops(home), "completed").toPath(), "not a directory");
    Files.writeString(new File(home, PluginInstallJournals.HISTORY_PROOF).toPath(), "{}");
    assertQuarantineRestoresGate(home);

    // 事务目录本身是文件
    home = temp.newFolder("root-file");
    Files.writeString(ops(home).toPath(), "not a directory");
    assertQuarantineRestoresGate(home);
  }

  @Test
  public void duplicateIsolatesBothSidesWithoutGuessing() throws Exception {
    File home = home();
    String id = UUID.randomUUID().toString();
    entry(new File(ops(home), "completed"), id, true);
    entry(ops(home), id, false);
    var found = reasons(home);
    assertTrue(
        found.contains(
            PluginInstallJournals.DIRECTORY + "/completed/" + id + "=PLUGIN_JOURNAL_DUPLICATE"));
    assertTrue(
        found.contains(PluginInstallJournals.DIRECTORY + "/" + id + "=PLUGIN_JOURNAL_DUPLICATE"));
    assertQuarantineRestoresGate(home);
  }

  @Test
  public void changedPlanAfterConfirmationIsRefusedAndNothingMoves() throws Exception {
    File home = home();
    File bad = new File(ops(home), UUID.randomUUID().toString());
    Files.writeString(bad.toPath(), "file");
    String confirmed = PluginJournalQuarantine.digest(PluginInstallJournals.problems(fs, home));
    File later = new File(ops(home), UUID.randomUUID().toString());
    Files.writeString(later.toPath(), "appeared after confirmation");
    IOException changed =
        assertThrows(IOException.class, () -> PluginJournalQuarantine.apply(fs, home, confirmed));
    assertEquals("PLUGIN_QUARANTINE_PLAN_CHANGED", changed.getMessage());
    assertTrue(bad.exists());
    assertTrue(later.exists());
    assertFalse(new File(home, PluginJournalQuarantine.DIRECTORY).exists());
  }

  @Test
  public void quarantineDirectoryIsPrivate() {
    assertTrue(PrivateRoots.dsh(PluginJournalQuarantine.DIRECTORY));
  }
}
