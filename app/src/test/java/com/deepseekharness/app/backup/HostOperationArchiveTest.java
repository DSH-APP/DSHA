package com.deepseekharness.app.backup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class HostOperationArchiveTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private File terminal(File files, String id) throws Exception {
    File root = HostOperationArchive.root(files);
    fs.directory(root);
    File operation = fs.child(root, id);
    fs.directory(operation);
    fs.atomic(
        operation,
        "operation.json",
        BackupJson.write(
            Map.of(
                "version",
                1L,
                "id",
                id,
                "stage",
                "FINISHED",
                "busy",
                false,
                "updatedAt",
                1L,
                "result",
                "COMPLETE",
                "error",
                "",
                "artifact",
                ""),
            16384));
    return operation;
  }

  @Test
  public void completedHistoryDoesNotLockThe65thOr129thOperation() throws Exception {
    File files = temp.newFolder();
    for (int index = 1; index <= 130; index++) {
      File root = HostOperationArchive.reserve(fs, files);
      File operation = terminalAt(root, UUID.randomUUID().toString());
      if (index == 65) assertEquals(64, fs.list(HostOperationArchive.completedRoot(files)).size());
      if (index == 129)
        assertEquals(128, fs.list(HostOperationArchive.completedRoot(files)).size());
      HostOperationArchive.archiveIfTerminal(fs, files, operation);
    }
    assertEquals(130, fs.list(HostOperationArchive.completedRoot(files)).size());
    assertEquals(
        List.of(HostOperationArchive.COMPLETED), fs.list(HostOperationArchive.root(files)));
  }

  private File terminalAt(File root, String id) throws Exception {
    File operation = fs.child(root, id);
    fs.directory(operation);
    fs.atomic(
        operation,
        "operation.json",
        BackupJson.write(
            Map.of(
                "version",
                1L,
                "id",
                id,
                "stage",
                "FINISHED",
                "busy",
                false,
                "updatedAt",
                1L,
                "result",
                "COMPLETE",
                "error",
                "",
                "artifact",
                ""),
            16384));
    return operation;
  }

  @Test
  public void missingOperationRootHasNoHistoryRoots() throws Exception {
    File missing = new File(temp.newFolder(), "host-backup-operations");
    assertTrue(HostOperationArchive.roots(fs, missing).isEmpty());
  }

  @Test
  public void legacyTerminalRestoreStagingIsSafelyMigratedBeforeThe65thSlot() throws Exception {
    File files = temp.newFolder(), root = HostOperationArchive.reserve(fs, files);
    List<String> ids = new ArrayList<>();
    for (int index = 0; index < 65; index++) {
      String id = UUID.randomUUID().toString();
      ids.add(id);
      File operation = fs.child(root, id);
      fs.directory(operation);
      File payload = fs.child(operation, "payload");
      fs.directory(payload);
      fs.atomic(payload, "0", "synthetic decrypted record".getBytes());
      fs.atomic(operation, "restore.dshdata", "synthetic plaintext".getBytes());
      fs.atomic(operation, "input.archive", "preserve selected input".getBytes());
      fs.atomic(
          operation,
          "operation.json",
          BackupJson.write(
              Map.of(
                  "version",
                  1L,
                  "id",
                  id,
                  "stage",
                  "FINISHED",
                  "busy",
                  false,
                  "updatedAt",
                  index + 1L,
                  "result",
                  "DATA_RESTORED",
                  "error",
                  "",
                  "artifact",
                  ""),
              16384));
    }
    File available = HostOperationArchive.reserve(fs, files);
    assertTrue(HostOperationArchive.activeEntries(fs, available).isEmpty());
    assertEquals(65, fs.list(HostOperationArchive.completedRoot(files)).size());
    for (String id : ids) {
      File operation = HostOperationArchive.locate(fs, available, id);
      assertEquals("MISSING", fs.stat(fs.child(operation, "restore.dshdata")).type);
      assertEquals("MISSING", fs.stat(fs.child(operation, "payload")).type);
      assertEquals(
          "preserve selected input",
          new String(fs.small(fs.child(operation, "input.archive"), 100)));
    }
  }

  @Test
  public void verifiedManualCopyRemainsAddressableAfterArchiving() throws Exception {
    File files = temp.newFolder(), operations = HostOperationArchive.reserve(fs, files);
    String id = UUID.randomUUID().toString();
    File operation = fs.child(operations, id);
    fs.directory(operation);
    byte[] artifact = new byte[80];
    System.arraycopy(PortableBackupCrypto.MAGIC, 0, artifact, 0, PortableBackupCrypto.MAGIC.length);
    try (OutputStream out = fs.create(fs.child(operation, "portable.dshbak"))) {
      out.write(artifact);
    }
    String sha = BackupArchive.hex(BackupArchive.sha().digest(artifact));
    fs.atomic(
        operation,
        "verified.json",
        BackupJson.write(
            Map.of(
                "encryptedSha256",
                sha,
                "encryptedBytes",
                (long) artifact.length,
                "entries",
                0L,
                "integrity",
                "QUIESCENT",
                "requestedScope",
                "application",
                "createdAt",
                1L),
            BackupLimits.MANIFEST));
    fs.atomic(
        operation,
        "operation.json",
        BackupJson.write(
            Map.of(
                "version",
                1L,
                "id",
                id,
                "stage",
                "FINISHED",
                "busy",
                false,
                "updatedAt",
                1L,
                "result",
                "COMPLETE",
                "error",
                "",
                "artifact",
                "portable.dshbak"),
            16384));
    HostOperationArchive.archiveIfTerminal(fs, files, operation);
    VerifiedBackupCopy copy = VerifiedBackupCopy.inspect(fs, operations, id);
    copy.verify(fs, new BackupControl(null));
    assertEquals(
        HostOperationArchive.completedRoot(files), copy.artifact.getParentFile().getParentFile());
    assertEquals(id, HostOperationArchive.locate(fs, operations, id).getName());
  }

  @Test
  public void duplicateAndInterruptedMoveAreResolvedFailClosed() throws Exception {
    File files = temp.newFolder(), operations = HostOperationArchive.reserve(fs, files);
    String id = UUID.randomUUID().toString();
    File operation = terminalAt(operations, id);
    BackupFileSystem interrupted =
        (BackupFileSystem)
            java.lang.reflect.Proxy.newProxyInstance(
                BackupFileSystem.class.getClassLoader(),
                new Class[] {BackupFileSystem.class},
                (proxy, method, args) -> {
                  try {
                    Object result = method.invoke(fs, args);
                    if (method.getName().equals("move") && ((File) args[0]).equals(operation))
                      throw new IOException("process interrupted after rename");
                    return result;
                  } catch (java.lang.reflect.InvocationTargetException error) {
                    throw error.getCause();
                  }
                });
    try {
      HostOperationArchive.archiveIfTerminal(interrupted, files, operation);
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("interrupted"));
    }
    assertEquals(
        HostOperationArchive.completedRoot(files),
        HostOperationArchive.locate(fs, operations, id).getParentFile());
    File duplicate = terminalAt(operations, id);
    try {
      HostOperationArchive.locate(fs, operations, id);
      fail();
    } catch (IOException expected) {
      assertEquals("OPERATION_DUPLICATE", expected.getMessage());
    }
    assertTrue(fs.stat(duplicate).type.equals("DIRECTORY"));
  }

  @Test
  public void unknownOrBusyOriginalsAreNeverArchivedOrDeleted() throws Exception {
    File files = temp.newFolder(), root = HostOperationArchive.reserve(fs, files);
    String id = UUID.randomUUID().toString();
    File busy = fs.child(root, id);
    fs.directory(busy);
    fs.atomic(
        busy,
        "operation.json",
        BackupJson.write(
            Map.of(
                "version",
                1L,
                "id",
                id,
                "stage",
                "PREPARING",
                "busy",
                true,
                "updatedAt",
                1L,
                "result",
                "",
                "error",
                "",
                "artifact",
                ""),
            16384));
    File unknown = fs.child(root, "manual-originals");
    fs.directory(unknown);
    fs.atomic(unknown, "notes", "retain".getBytes());
    HostOperationArchive.reserve(fs, files);
    assertTrue(fs.stat(busy).type.equals("DIRECTORY"));
    assertEquals("retain", new String(fs.small(fs.child(unknown, "notes"), 20)));
    assertEquals("MISSING", fs.stat(new File(HostOperationArchive.completedRoot(files), id)).type);
  }

  @Test
  public void completedHistoryIsVerifiedOnceThenOrdinaryGateReadsOnlyActive() throws Exception {
    for (int count : new int[] {0, 65, 1000}) {
      File files = temp.newFolder(),
          root = HostOperationArchive.root(files),
          history = HostOperationArchive.completedRoot(files);
      fs.directory(root);
      fs.directory(history);
      for (int index = 0; index < count; index++) {
        String id = UUID.randomUUID().toString();
        File operation = new File(history, id);
        Files.createDirectory(operation.toPath());
        Files.write(
            new File(operation, "operation.json").toPath(),
            BackupJson.write(
                Map.of(
                    "version",
                    1L,
                    "id",
                    id,
                    "stage",
                    "FINISHED",
                    "busy",
                    false,
                    "updatedAt",
                    index + 1L,
                    "result",
                    "COMPLETE",
                    "error",
                    "",
                    "artifact",
                    ""),
                16384));
      }
      int[] historyLists = {0}, calls = {0};
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
                      return method.invoke(fs, args);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                      throw error.getCause();
                    }
                  });
      assertTrue(HostPendingTransactions.pending(counted, files).isEmpty());
      assertEquals(1, historyLists[0]);
      historyLists[0] = 0;
      calls[0] = 0;
      long start = System.nanoTime();
      for (int repetition = 0; repetition < 5; repetition++)
        assertTrue(HostPendingTransactions.pending(counted, files).isEmpty());
      long elapsed = System.nanoTime() - start;
      assertEquals("completed history must be inert after its migration proof", 0, historyLists[0]);
      assertTrue("ordinary filesystem calls must not scale with completed count", calls[0] < 150);
      assertTrue("stable gate should finish promptly even with history", elapsed < 5_000_000_000L);
      System.out.println(
          "HOST_HISTORY_STABLE count="
              + count
              + " fsCalls="
              + calls[0]
              + " elapsedNanos="
              + elapsed);
    }
  }

  @Test
  public void legacyHistoryPendingBrokenAndDuplicateFailClosedWithoutDeletingOriginals()
      throws Exception {
    File files = temp.newFolder(),
        root = HostOperationArchive.root(files),
        history = HostOperationArchive.completedRoot(files);
    fs.directory(root);
    fs.directory(history);
    String id = UUID.randomUUID().toString();
    File entry = new File(history, id);
    fs.directory(entry);
    Files.write(new File(entry, "switching").toPath(), (id + "\nswitching\n").getBytes());
    try {
      HostPendingTransactions.pending(fs, files);
      fail();
    } catch (IOException expected) {
      assertEquals("OPERATION_HISTORY_NOT_TERMINAL", expected.getMessage());
    }
    assertTrue(entry.isDirectory());
    Files.delete(new File(entry, "switching").toPath());
    Files.write(new File(entry, "operation.json").toPath(), "broken".getBytes());
    try {
      HostPendingTransactions.pending(fs, files);
      fail();
    } catch (IOException expected) {
      assertTrue(entry.isDirectory());
    }
    Files.write(
        new File(entry, "operation.json").toPath(),
        BackupJson.write(
            Map.of(
                "version",
                1L,
                "id",
                id,
                "stage",
                "FINISHED",
                "busy",
                false,
                "updatedAt",
                1L,
                "result",
                "COMPLETE",
                "error",
                "",
                "artifact",
                ""),
            16384));
    assertTrue(HostPendingTransactions.pending(fs, files).isEmpty());
    terminalAt(root, id);
    try {
      HostPendingTransactions.pending(fs, files);
      fail();
    } catch (IOException expected) {
      assertEquals("OPERATION_DUPLICATE", expected.getMessage());
    }
    assertTrue(entry.isDirectory());
  }

  @Test
  public void activeSwitchingIsStillRecheckedAfterHistoricalMigration() throws Exception {
    File files = temp.newFolder(),
        root = HostOperationArchive.root(files),
        history = HostOperationArchive.completedRoot(files);
    fs.directory(root);
    fs.directory(history);
    assertTrue(HostPendingTransactions.pending(fs, files).isEmpty());
    String id = UUID.randomUUID().toString();
    File active = fs.child(root, id);
    fs.directory(active);
    Files.write(new File(active, "switching").toPath(), (id + "\nswitching\n").getBytes());
    Files.write(new File(active, "plan.json").toPath(), "{}".getBytes());
    assertEquals(List.of(active), HostPendingTransactions.pending(fs, files));
  }
}
