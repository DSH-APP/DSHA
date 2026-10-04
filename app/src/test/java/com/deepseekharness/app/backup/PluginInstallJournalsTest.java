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
}
