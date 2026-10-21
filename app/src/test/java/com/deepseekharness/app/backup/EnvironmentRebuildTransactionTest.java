package com.deepseekharness.app.backup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.*;

/** Isolated host filesystem fixtures for rebuild history and interruption gates. */
public final class EnvironmentRebuildTransactionTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  private static String digest(byte[] bytes) throws Exception {
    return BackupArchive.digest(new ByteArrayInputStream(bytes), new BackupControl(null));
  }

  @Test
  public void completedEnvironmentHistoryDoesNotIncreaseStablePendingCost() throws Exception {
    for (int count : new int[] {0, 65, 1000}) {
      File files = temp.newFolder(),
          home = new File(files, EnvironmentRebuildTransaction.HOME),
          completed = new File(home, "completed");
      Files.createDirectories(completed.toPath());
      for (int index = 0; index < count; index++) {
        String id = UUID.randomUUID().toString();
        File entry = new File(completed, id);
        Files.createDirectory(entry.toPath());
        Files.writeString(new File(entry, "committed").toPath(), id + "\ncommitted\n");
        Files.write(
            new File(entry, "intent.json").toPath(),
            BackupJson.write(
                Map.of(
                    "version",
                    1L,
                    "id",
                    id,
                    "had",
                    false,
                    "oldKey",
                    "",
                    "archive",
                    "a".repeat(64),
                    "mapping",
                    "b".repeat(64)),
                4096));
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
                    if (method.getName().equals("list") && ((File) args[0]).equals(completed))
                      historyLists[0]++;
                    try {
                      return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                      throw error.getCause();
                    }
                  });
      assertNull(EnvironmentRebuildTransaction.pending(counted, files));
      assertEquals(1, historyLists[0]);
      calls[0] = historyLists[0] = 0;
      long start = System.nanoTime();
      for (int i = 0; i < 5; i++) assertNull(EnvironmentRebuildTransaction.pending(counted, files));
      long elapsed = System.nanoTime() - start;
      assertEquals(0, historyLists[0]);
      assertTrue(calls[0] < 160);
      System.out.println(
          "ENV_HISTORY_STABLE count="
              + count
              + " fsCalls="
              + calls[0]
              + " elapsedNanos="
              + elapsed);
    }
  }

  private EnvironmentRebuildTransaction complete(int index) throws Exception {
    File linux = new File(temp.getRoot(), "linux");
    boolean had = linux.isDirectory();
    if (had) Files.writeString(new File(linux, "old-tree.txt").toPath(), "old-" + index);
    EnvironmentRebuildTransaction tx =
        EnvironmentRebuildTransaction.create(fs, temp.getRoot(), null);
    byte[] archive = ("verified personal snapshot " + index).getBytes(StandardCharsets.UTF_8);
    byte[] mapping = ("verified roots " + index).getBytes(StandardCharsets.UTF_8);
    Files.write(new File(tx.directory(), "data.dshdata").toPath(), archive);
    Files.write(new File(tx.directory(), "data-roots.json").toPath(), mapping);
    String archiveHash = digest(archive), mappingHash = digest(mapping);
    tx.prepare(archiveHash, mappingHash);
    tx.begin();
    tx.dataRestored(archiveHash);
    RuntimeDescriptor descriptor =
        ManagedRuntimeTransactionTest.descriptor((char) ('a' + index % 20));
    tx.commit(descriptor, ManagedRuntimeTransactionTest.health(descriptor));
    if (had) {
      tx.sealRetired(new BackupControl(null));
      assertTrue(tx.cleanupRetired(new BackupControl(null)));
    } else assertFalse(tx.cleanupRetired(new BackupControl(null)));
    EnvironmentRebuildTransaction.archiveCompleted(fs, temp.getRoot());
    return tx;
  }

  @Test
  public void ninthSuccessfulRebuildCanStartAndOlderArchivesStayDiscoverable() throws Exception {
    java.util.ArrayList<EnvironmentRebuildTransaction> history = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) history.add(complete(i));
    File home = new File(temp.getRoot(), EnvironmentRebuildTransaction.HOME);
    assertEquals(8, fs.list(new File(home, "completed")).size());

    EnvironmentRebuildTransaction ninth =
        EnvironmentRebuildTransaction.create(fs, temp.getRoot(), null);
    Files.writeString(
        new File(ninth.directory(), "data.dshdata").toPath(), "uncommitted personal snapshot");
    assertEquals(8, fs.list(new File(home, "completed")).size());
    for (EnvironmentRebuildTransaction previous : history) {
      assertTrue(new File(previous.directory(), "data.dshdata").isFile());
      assertTrue(new File(previous.directory(), "data-roots.json").isFile());
      assertTrue(new File(previous.directory(), "committed").isFile());
    }
  }

  @Test
  public void readOnlyPendingCannotMoveWorkBetweenCommitAndRetiredCleanup() throws Exception {
    EnvironmentRebuildTransaction first = complete(0);
    File linux = new File(temp.getRoot(), "linux");
    Files.writeString(new File(linux, "old-tree.txt").toPath(), "old");
    EnvironmentRebuildTransaction tx =
        EnvironmentRebuildTransaction.create(fs, temp.getRoot(), null);
    byte[] archive = "personal".getBytes(StandardCharsets.UTF_8),
        mapping = "roots".getBytes(StandardCharsets.UTF_8);
    Files.write(new File(tx.directory(), "data.dshdata").toPath(), archive);
    Files.write(new File(tx.directory(), "data-roots.json").toPath(), mapping);
    tx.prepare(digest(archive), digest(mapping));
    tx.begin();
    tx.dataRestored(digest(archive));
    RuntimeDescriptor descriptor = ManagedRuntimeTransactionTest.descriptor('z');
    tx.commit(descriptor, ManagedRuntimeTransactionTest.health(descriptor));
    File active = tx.directory();
    assertNull(EnvironmentRebuildTransaction.pending(fs, temp.getRoot()));
    assertEquals(active, tx.directory());
    assertTrue(active.isDirectory());
    tx.sealRetired(new BackupControl(null));
    assertTrue(tx.cleanupRetired(new BackupControl(null)));
    EnvironmentRebuildTransaction.archiveCompleted(fs, temp.getRoot());
    assertEquals("completed", tx.directory().getParentFile().getName());
    assertTrue(new File(first.directory(), "data.dshdata").isFile());
  }

  @Test
  public void emptyMarkedPlaceholderIsReclaimedButPartialAndUnknownRecordsRemain()
      throws Exception {
    File home = new File(temp.getRoot(), EnvironmentRebuildTransaction.HOME);
    assertTrue(home.mkdirs());
    EnvironmentRebuildTransaction empty =
        EnvironmentRebuildTransaction.create(fs, temp.getRoot(), null);
    File emptyPath = empty.directory();
    EnvironmentRebuildTransaction next =
        EnvironmentRebuildTransaction.create(fs, temp.getRoot(), null);
    assertFalse(emptyPath.exists());

    File partial = new File(next.directory(), "data.dshdata");
    Files.writeString(partial.toPath(), "unverified partial archive");
    File unknown = new File(home, "11111111-1111-4111-8111-111111111111");
    assertTrue(unknown.mkdir());
    Files.writeString(new File(unknown, "unique.txt").toPath(), "keep unknown");
    EnvironmentRebuildTransaction after =
        EnvironmentRebuildTransaction.create(fs, temp.getRoot(), null);
    assertTrue(partial.isFile());
    assertTrue(new File(unknown, "unique.txt").isFile());
    assertTrue(after.directory().isDirectory());
  }

  @Test
  public void switchingRecordStillBlocksAfterMoreThanEightCompletedRows() throws Exception {
    // Exercise both the historical create threshold (8) and the old pending
    // scan threshold (32) using real committed journals and data archives.
    for (int i = 0; i < 33; i++) complete(i);
    EnvironmentRebuildTransaction active =
        EnvironmentRebuildTransaction.create(fs, temp.getRoot(), null);
    byte[] archive = "archive".getBytes(StandardCharsets.UTF_8),
        mapping = "mapping".getBytes(StandardCharsets.UTF_8);
    Files.write(new File(active.directory(), "data.dshdata").toPath(), archive);
    Files.write(new File(active.directory(), "data-roots.json").toPath(), mapping);
    active.prepare(digest(archive), digest(mapping));
    active.begin();
    assertEquals(
        active.directory().getName(),
        EnvironmentRebuildTransaction.pending(fs, temp.getRoot()).directory().getName());
    try {
      EnvironmentRebuildTransaction.create(fs, temp.getRoot(), null);
      fail("unclosed switch must block a new rebuild");
    } catch (java.io.IOException expected) {
      assertEquals("ENVIRONMENT_RECOVERY_REQUIRED", expected.getMessage());
    }
  }

  @Test
  public void failureAtEveryBeginBoundaryRollsBackTheOriginalAndKeepsSnapshots() throws Exception {
    for (String boundary : List.of("switching", "old-moved", "candidate-created")) {
      File files = temp.newFolder(),
          linux = new File(files, "linux"),
          original = new File(linux, "ubuntu/root/user.txt");
      Files.createDirectories(original.getParentFile().toPath());
      Files.writeString(original.toPath(), "original-" + boundary);
      String oldKey = fs.stat(linux).key;
      EnvironmentRebuildTransaction tx =
          prepared(
              files,
              true,
              name -> {
                if (name.equals(boundary)) throw new java.io.IOException("FAULT_" + name);
              });
      assertEquals(
          "FAULT_" + boundary, assertThrows(java.io.IOException.class, tx::begin).getMessage());
      assertNotNull(EnvironmentRebuildTransaction.pending(fs, files));
      tx.rollback();
      assertNull(EnvironmentRebuildTransaction.pending(fs, files));
      assertEquals(oldKey, fs.stat(linux).key);
      assertEquals("original-" + boundary, Files.readString(original.toPath()));
      assertTrue(new File(tx.directory(), "data.dshdata").isFile());
      assertTrue(new File(tx.directory(), "data-roots.json").isFile());
      assertTrue(new File(tx.directory(), "rolled-back").isFile());
    }
  }

  @Test
  public void noPriorEnvironmentFailureRetainsCandidateAndRemainsVisibleAfterRollback()
      throws Exception {
    File files = temp.newFolder(), linux = new File(files, "linux");
    EnvironmentRebuildTransaction tx = prepared(files, false, null);
    tx.begin();
    File original = new File(linux, "ubuntu/root/.dsh/sessions/owned.txt");
    Files.createDirectories(original.getParentFile().toPath());
    Files.writeString(original.toPath(), "retained candidate data");
    String candidateKey = fs.stat(linux).key;
    tx.rollback();
    assertFalse(linux.exists());
    assertNull(EnvironmentRebuildTransaction.pending(fs, files));
    File failed = new File(tx.directory(), "failed-linux");
    assertEquals(candidateKey, fs.stat(failed).key);
    assertEquals(
        "retained candidate data",
        Files.readString(new File(failed, "ubuntu/root/.dsh/sessions/owned.txt").toPath()));
    List<RetainedCatalogue.Entry> retained =
        new RetainedCatalogue(fs, files, new File(files, "user-data-v5/dsh")).list();
    assertTrue(
        retained.stream()
            .anyMatch(
                entry ->
                    entry.kind == RetainedCatalogue.Kind.ENVIRONMENT
                        && entry.id.equals(tx.directory().getName())
                        && entry.part.equals("failed-linux-data")
                        && entry.status.equals("ROLLED_BACK")));
    assertTrue(new File(tx.directory(), "data.dshdata").isFile());
    assertTrue(new File(tx.directory(), "data-roots.json").isFile());
  }

  @Test
  public void rollbackBeforeSwitchingDoesNotTouchCurrentEnvironment() throws Exception {
    File files = temp.newFolder(), original = new File(files, "linux/ubuntu/root/user.txt");
    Files.createDirectories(original.getParentFile().toPath());
    Files.writeString(original.toPath(), "pre-switch original");
    EnvironmentRebuildTransaction tx = prepared(files, true, null);
    tx.rollback();
    assertEquals("pre-switch original", Files.readString(original.toPath()));
    assertFalse(new File(tx.directory(), "rolled-back").exists());
    assertNull(EnvironmentRebuildTransaction.pending(fs, files));
  }

  private EnvironmentRebuildTransaction prepared(
      File files, boolean had, HostDataTransaction.Fault fault) throws Exception {
    assertEquals(had, new File(files, "linux").isDirectory());
    EnvironmentRebuildTransaction tx = EnvironmentRebuildTransaction.create(fs, files, fault);
    byte[] archive = "private protected snapshot".getBytes(StandardCharsets.UTF_8),
        mapping = "fixed-root mapping".getBytes(StandardCharsets.UTF_8);
    Files.write(new File(tx.directory(), "data.dshdata").toPath(), archive);
    Files.write(new File(tx.directory(), "data-roots.json").toPath(), mapping);
    tx.prepare(digest(archive), digest(mapping));
    return tx;
  }
}
