package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class PluginInstallJournalsTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  private File operation() throws Exception {
    File result =
        new File(temp.getRoot(), PluginInstallJournals.DIRECTORY + "/" + UUID.randomUUID());
    assertTrue(result.mkdirs());
    return result;
  }

  private File completed(File work) {
    return new File(work.getParentFile(), "completed/" + work.getName());
  }

  private void plan(File work) throws Exception {
    Files.write(
        new File(work, "plan.json").toPath(),
        BackupJson.write(Map.of("format", 2L, "id", work.getName()), 4096));
  }

  @Test
  public void onlyPreparedUnfinishedOperationsBlockMaintenance() throws Exception {
    File work = operation();
    assertTrue(PluginInstallJournals.pending(fs, temp.getRoot()).isEmpty());
    plan(work);
    assertEquals(List.of(work), PluginInstallJournals.pending(fs, temp.getRoot()));
    Files.writeString(new File(work, "committed").toPath(), work.getName() + "\ncommitted\n");
    assertTrue(PluginInstallJournals.pending(fs, temp.getRoot()).isEmpty());
    assertTrue(
        "read-only pending must not move a caller's just-committed work", work.isDirectory());
    PluginInstallJournals.archiveCompleted(fs, temp.getRoot());
    assertTrue(completed(work).isDirectory());
    Files.writeString(new File(temp.getRoot(), "new-user-content").toPath(), "later data");
    assertTrue(PluginInstallJournals.pending(fs, temp.getRoot()).isEmpty());
    assertEquals(
        "later data", Files.readString(new File(temp.getRoot(), "new-user-content").toPath()));
  }

  @Test
  public void malformedMarkersAndUnknownDirectoriesAreNotEmptyState() throws Exception {
    File work = operation();
    Files.writeString(new File(work, "committed").toPath(), "untrusted");
    assertThrows(IOException.class, () -> PluginInstallJournals.pending(fs, temp.getRoot()));
  }

  @Test
  public void recoveredJournalRemainsInspectableWithoutBlockingNewWork() throws Exception {
    File work = operation();
    plan(work);
    Files.writeString(new File(work, "rolled-back").toPath(), work.getName() + "\nrolled-back\n");
    Files.writeString(new File(work, "retained-original").toPath(), "original");
    assertTrue(PluginInstallJournals.pending(fs, temp.getRoot()).isEmpty());
    PluginInstallJournals.archiveCompleted(fs, temp.getRoot());
    assertEquals(
        "original", Files.readString(new File(completed(work), "retained-original").toPath()));
  }

  @Test
  public void completedPluginHistoryDoesNotIncreaseStablePendingCost() throws Exception {
    for (int count : new int[] {0, 65, 1000}) {
      File home = temp.newFolder(),
          root = new File(home, PluginInstallJournals.DIRECTORY),
          history = new File(root, "completed");
      Files.createDirectories(history.toPath());
      for (int index = 0; index < count; index++) {
        String id = UUID.randomUUID().toString();
        File entry = new File(history, id);
        Files.createDirectory(entry.toPath());
        Files.writeString(new File(entry, "committed").toPath(), id + "\ncommitted\n");
        Files.write(
            new File(entry, "plan.json").toPath(),
            BackupJson.write(Map.of("format", 2L, "id", id), 4096));
      }
      JvmBackupFileSystem delegate = new JvmBackupFileSystem();
      int[] calls = {0}, historyLists = {0};
      BackupFileSystem counted =
          (BackupFileSystem)
              java.lang.reflect.Proxy.newProxyInstance(
                  BackupFileSystem.class.getClassLoader(),
                  new Class[] {BackupFileSystem.class},
                  (proxy, method, args) -> {
                    calls[0]++;
                    if (method.getName().equals("list") && ((File) args[0]).equals(history))
                      historyLists[0]++;
                    try {
                      return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                      throw error.getCause();
                    }
                  });
      assertTrue(PluginInstallJournals.pending(counted, home).isEmpty());
      assertEquals(1, historyLists[0]);
      calls[0] = historyLists[0] = 0;
      long start = System.nanoTime();
      for (int i = 0; i < 5; i++)
        assertTrue(PluginInstallJournals.pending(counted, home).isEmpty());
      long elapsed = System.nanoTime() - start;
      assertEquals(0, historyLists[0]);
      assertTrue(calls[0] < 160);
      System.out.println(
          "PLUGIN_HISTORY_STABLE count="
              + count
              + " fsCalls="
              + calls[0]
              + " elapsedNanos="
              + elapsed);
    }
  }

  @Test
  public void unfinishedAndDuplicateRemainBlockedAfterHistoryMigration() throws Exception {
    File home = temp.newFolder(),
        root = new File(home, PluginInstallJournals.DIRECTORY),
        history = new File(root, "completed");
    Files.createDirectories(history.toPath());
    String id = UUID.randomUUID().toString();
    File retained = new File(history, id);
    Files.createDirectory(retained.toPath());
    Files.writeString(new File(retained, "committed").toPath(), id + "\ncommitted\n");
    Files.write(
        new File(retained, "plan.json").toPath(),
        BackupJson.write(Map.of("format", 2L, "id", id), 4096));
    assertTrue(PluginInstallJournals.pending(fs, home).isEmpty());
    File active = new File(root, UUID.randomUUID().toString());
    Files.createDirectory(active.toPath());
    plan(active);
    assertEquals(List.of(active), PluginInstallJournals.pending(fs, home));
    File duplicate = new File(root, id);
    Files.createDirectory(duplicate.toPath());
    plan(duplicate);
    assertThrows(IOException.class, () -> PluginInstallJournals.pending(fs, home));
    assertTrue(retained.isDirectory());
  }

  /** 复现 PLUGIN_JOURNAL_ID 死循环：旧版目录内完成证明的原子写残留不得让门禁永久不可读。 */
  @Test
  public void legacyInDirectoryProofResidueNeverBlocksMaintenance() throws Exception {
    File home = temp.newFolder(),
        root = new File(home, PluginInstallJournals.DIRECTORY),
        history = new File(root, "completed");
    Files.createDirectories(history.toPath());
    String done = UUID.randomUUID().toString();
    File retained = new File(history, done);
    Files.createDirectory(retained.toPath());
    Files.writeString(new File(retained, "committed").toPath(), done + "\ncommitted\n");
    Files.write(
        new File(retained, "plan.json").toPath(),
        BackupJson.write(Map.of("format", 2L, "id", done), 4096));
    String legacy = ".completed-proof-v1.json";
    List<String> residue =
        List.of(
            legacy,
            legacy + ".previous",
            legacy + ".tmp-" + UUID.randomUUID(),
            legacy + ".previous-retained-" + UUID.randomUUID(),
            legacy + ".restore-" + UUID.randomUUID());
    for (String name : residue)
      Files.writeString(new File(root, name).toPath(), "{\"version\":1,\"homeKey\":\"stale\"}");

    assertTrue(PluginInstallJournals.pending(fs, home).isEmpty());
    File active = new File(root, UUID.randomUUID().toString());
    Files.createDirectory(active.toPath());
    plan(active);
    assertEquals(List.of(active), PluginInstallJournals.pending(fs, home));
    Files.writeString(new File(active, "committed").toPath(), active.getName() + "\ncommitted\n");
    PluginInstallJournals.archiveCompleted(fs, home);
    assertTrue(new File(history, active.getName()).isDirectory());
    assertTrue(PluginInstallJournals.pending(fs, home).isEmpty());

    // 残留原样保留；新证明只写在事务目录之外。
    for (String name : residue) assertTrue(name, new File(root, name).isFile());
    assertTrue(new File(home, PluginInstallJournals.HISTORY_PROOF).isFile());
    for (String name : fs.list(root))
      assertFalse(name, name.startsWith(PluginInstallJournals.HISTORY_PROOF));
  }

  /** 只覆盖抽象原语；atomic() 等默认方法在本对象上执行，其内部 move 会经过故障注入。 */
  private static final class InterruptSecondMove implements BackupFileSystem {
    private final BackupFileSystem delegate;
    private int moves;

    InterruptSecondMove(BackupFileSystem delegate) {
      this.delegate = delegate;
    }

    public Node stat(File file) throws IOException {
      return delegate.stat(file);
    }

    public String readLink(File file) throws IOException {
      return delegate.readLink(file);
    }

    public List<String> list(File directory) throws IOException {
      return delegate.list(directory);
    }

    public InputStream read(File file, Node expected) throws IOException {
      return delegate.read(file, expected);
    }

    public OutputStream create(File file) throws IOException {
      return delegate.create(file);
    }

    public void directory(File file) throws IOException {
      delegate.directory(file);
    }

    public void move(File source, File target) throws IOException {
      if (++moves == 2) throw new IOException("PROCESS_INTERRUPTED");
      delegate.move(source, target);
    }

    public void delete(File file) throws IOException {
      delegate.delete(file);
    }

    public void syncDirectory(File directory) throws IOException {
      delegate.syncDirectory(directory);
    }

    public void mode(File file, int mode) throws IOException {
      delegate.mode(file, mode);
    }

    public void symlink(String target, File link) throws IOException {
      delegate.symlink(target, link);
    }
  }

  /** 证明写到一半被杀：新位置的原子写残留同样不进入被扫描目录。 */
  @Test
  public void interruptedProofWriteLeavesTransactionDirectoryScannable() throws Exception {
    File home = temp.newFolder(),
        root = new File(home, PluginInstallJournals.DIRECTORY),
        history = new File(root, "completed");
    Files.createDirectories(history.toPath());
    PluginInstallJournals.pending(fs, home);
    File proof = new File(home, PluginInstallJournals.HISTORY_PROOF);
    assertTrue(proof.isFile());
    // 证明失配迫使下一次校验重写；第二次 move（target -> previous 之后的发布）被“杀”。
    Files.writeString(
        proof.toPath(), "{\"version\":1,\"homeKey\":\"stale\",\"historyKey\":\"stale\"}");
    BackupFileSystem faulty = new InterruptSecondMove(fs);
    assertThrows(IOException.class, () -> PluginInstallJournals.pending(faulty, home));
    assertTrue(new File(home, PluginInstallJournals.HISTORY_PROOF + ".previous").isFile());
    assertEquals(List.of("completed"), fs.list(root));
    // 下一次正常探测从 previous 恢复并完成，不需要人工清理。
    assertTrue(PluginInstallJournals.pending(fs, home).isEmpty());
    assertTrue(proof.isFile());
  }

  /** 外来条目（含大写 UUID、非 UUID 目录内带 plan.json）保留原件，但不阻塞也不被当作事务。 */
  @Test
  public void foreignEntriesAreRetainedWithoutBlockingOrReplay() throws Exception {
    File home = temp.newFolder(), root = new File(home, PluginInstallJournals.DIRECTORY);
    Files.createDirectories(new File(root, "completed").toPath());
    String upper = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
    File foreign = new File(root, upper);
    Files.createDirectory(foreign.toPath());
    Files.write(
        new File(foreign, "plan.json").toPath(),
        BackupJson.write(Map.of("format", 2L, "id", upper), 4096));
    Files.writeString(new File(root, ".DS_Store").toPath(), "file manager");
    File foreignHistory = new File(root, "completed/notes");
    Files.writeString(foreignHistory.toPath(), "user note");

    assertTrue(PluginInstallJournals.pending(fs, home).isEmpty());
    PluginInstallJournals.archiveCompleted(fs, home);
    assertTrue(new File(foreign, "plan.json").isFile());
    assertEquals("file manager", Files.readString(new File(root, ".DS_Store").toPath()));
    assertEquals("user note", Files.readString(foreignHistory.toPath()));
  }

  @Test
  public void proofResidueBesideDataRootStaysPrivate() {
    assertTrue(PrivateRoots.dsh(PluginInstallJournals.HISTORY_PROOF));
    assertTrue(PrivateRoots.dsh(PluginInstallJournals.HISTORY_PROOF + ".previous"));
    assertTrue(PrivateRoots.dsh(PluginInstallJournals.HISTORY_PROOF + ".tmp-" + UUID.randomUUID()));
    assertTrue(PrivateRoots.dsh(PluginInstallJournals.DIRECTORY));
    assertFalse(PrivateRoots.dsh("sessions"));
    assertFalse(PrivateRoots.dsh(PluginInstallJournals.HISTORY_PROOF + "-user-copy"));
  }

  @Test
  public void casMaterializationFormatThreeStaysPendingUntilExplicitTerminalProof()
      throws Exception {
    File work = operation();
    Files.write(
        new File(work, "plan.json").toPath(),
        BackupJson.write(
            Map.of(
                "format",
                3L,
                "id",
                work.getName(),
                "name",
                "dsha-pnpm-cas-copy",
                "operation",
                "materialize-pnpm-cas-files-v1",
                "casFingerprint",
                "pnpm-v10-sha512",
                "entries",
                List.of(Map.of("path", "package/index.js", "sha512", "a".repeat(128)))),
            BackupLimits.MANIFEST));
    Files.writeString(new File(work, "before-0").toPath(), "retained original link fixture");
    assertEquals(List.of(work), PluginInstallJournals.pending(fs, temp.getRoot()));
    assertEquals(List.of(work), PluginInstallJournals.pending(fs, temp.getRoot()));
    assertTrue("Read-only pending must not replay a CAS repair", work.isDirectory());
    Files.writeString(new File(work, "committed").toPath(), work.getName() + "\ncommitted\n");
    PluginInstallJournals.archiveCompleted(fs, temp.getRoot());
    assertTrue(PluginInstallJournals.pending(fs, temp.getRoot()).isEmpty());
    assertEquals(
        "retained original link fixture",
        Files.readString(new File(completed(work), "before-0").toPath()));
  }
}
