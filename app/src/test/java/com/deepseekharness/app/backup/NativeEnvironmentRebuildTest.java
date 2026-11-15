package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** 宿主数据/目录事务使用真实临时文件。没有旧 Bash/Python，也不接触设备数据。 */
public class NativeEnvironmentRebuildTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  static final JvmBackupFileSystem FS = new JvmBackupFileSystem();

  static void put(File root, String path, String value) throws Exception {
    File file = new File(root, path);
    Files.createDirectories(file.getParentFile().toPath());
    Files.writeString(file.toPath(), value);
  }

  static void fixture(File files) throws Exception {
    put(files, "linux/ubuntu/root/.dsh/sessions/a", "conversation");
    put(files, "linux/ubuntu/root/.dsh/profiles/web/package.json", "{}");
    put(files, "linux/ubuntu/root/.cache/unique-build", "custom cache content");
    put(files, "linux/ubuntu/root/project/.plugins.lock", "user file, not the app lock");
    put(files, "linux/ubuntu/usr/bin/system-tool", "old system");
    put(
        files,
        "linux/ubuntu/usr/local/lib/node_modules/user-plugin/package.json",
        "{\"name\":\"user-plugin\"}");
    put(
        files,
        "linux/ubuntu/usr/local/lib/node_modules/user-plugin/node_modules/dep/value",
        "dependency");
    put(files, "linux/ubuntu/data/personal/value", "real data under data");
    put(files, "linux/ubuntu/opt/user-tool/bin/tool", "user installed tool");
    put(files, "linux/ubuntu/opt/gh/bin/gh", "old managed gh");
    put(files, "linux/ubuntu/usr/local/bin/user-entry", "user file in system tree");
    put(files, "native-state", "same encrypted credentials and device grants");
  }

  static String hash(File path) throws Exception {
    try (InputStream input = new FileInputStream(path)) {
      return BackupArchive.digest(input, new BackupControl(null));
    }
  }

  static EnvironmentRebuildTransaction prepared(File files) throws Exception {
    var transaction = EnvironmentRebuildTransaction.create(FS, files, null);
    var data =
        new MaintenanceDataSnapshot(FS, files, transaction.directory(), new File(files, "public"));
    data.capture("/root/project", "synthetic-rc2.1", new BackupControl(null));
    transaction.prepare(hash(data.archive()), hash(data.mapping()));
    return transaction;
  }

  @Test
  public void hostSnapshotPreservesDataAndCustomDependenciesWithoutSystemArchive()
      throws Exception {
    File files = temp.newFolder();
    fixture(files);
    var transaction = prepared(files);
    List<String> roots = new ArrayList<>();
    try (InputStream input =
        new FileInputStream(new File(transaction.directory(), "data.dshdata"))) {
      var manifest = BackupArchive.read(input, null, new BackupControl(null));
      assertEquals("MAINTENANCE", manifest.get("operation"));
    }
    String map = Files.readString(new File(transaction.directory(), "data-roots.json").toPath());
    assertFalse(map.contains("usr/bin/system-tool"));
    transaction.begin();
    put(files, "linux/ubuntu/usr/bin/system-tool", "new system");
    new MaintenanceDataSnapshot(FS, files, transaction.directory(), new File(files, "public"))
        .restore(new File(files, "linux/ubuntu"), new BackupControl(null));
    assertEquals(
        "new system",
        Files.readString(new File(files, "linux/ubuntu/usr/bin/system-tool").toPath()));
    for (String path :
        List.of(
            "root/.dsh/sessions/a",
            "root/.cache/unique-build",
            "root/project/.plugins.lock",
            "usr/local/lib/node_modules/user-plugin/node_modules/dep/value",
            "data/personal/value"))
      assertArrayEquals(
          Files.readAllBytes(
              new File(transaction.directory(), "previous-linux/ubuntu/" + path).toPath()),
          Files.readAllBytes(new File(files, "linux/ubuntu/" + path).toPath()));
    assertEquals(
        "same encrypted credentials and device grants",
        Files.readString(new File(files, "native-state").toPath()));
  }

  @Test
  public void localRebuildKeepsAdbPairingKeysButNotOtherMachineState() throws Exception {
    File files = temp.newFolder();
    fixture(files);
    put(files, "linux/ubuntu/root/.dsh/adbkeys/adbkey", "local pairing key");
    put(files, "linux/ubuntu/root/.dsh/adbkeys/adbkey.pub", "local pairing pub");
    put(files, "linux/ubuntu/root/.dsh/.bridge_token", "old bridge token");
    var transaction = prepared(files);
    transaction.begin();
    put(files, "linux/ubuntu/usr/bin/system-tool", "new system");
    new MaintenanceDataSnapshot(FS, files, transaction.directory(), new File(files, "public"))
        .restore(new File(files, "linux/ubuntu"), new BackupControl(null));
    // adbkeys 已不进用户备份，但本机重建只在私有目录间迁移，原配对保留。
    assertEquals(
        "local pairing key",
        Files.readString(new File(files, "linux/ubuntu/root/.dsh/adbkeys/adbkey").toPath()));
    assertEquals(
        "local pairing pub",
        Files.readString(new File(files, "linux/ubuntu/root/.dsh/adbkeys/adbkey.pub").toPath()));
    assertFalse(new File(files, "linux/ubuntu/root/.dsh/.bridge_token").exists());
  }

  @Test
  public void failureAndRepeatedRollbackKeepOriginalAndFailedTrees() throws Exception {
    File files = temp.newFolder();
    fixture(files);
    var task = prepared(files);
    task.begin();
    put(files, "linux/ubuntu/partial", "new candidate");
    task.rollback();
    task.rollback();
    assertEquals(
        "conversation",
        Files.readString(new File(files, "linux/ubuntu/root/.dsh/sessions/a").toPath()));
    assertEquals(
        "new candidate",
        Files.readString(new File(task.directory(), "failed-linux/ubuntu/partial").toPath()));
    assertNull(EnvironmentRebuildTransaction.pending(FS, files));
  }

  @Test
  public void captureParentLinkIsIdentifiedWithoutChangingOriginalData() throws Exception {
    File files = temp.newFolder();
    fixture(files);
    var task = EnvironmentRebuildTransaction.create(FS, files, null);
    File home = new File(files, "linux/ubuntu/root");
    BackupFileSystem guarded =
        new JvmBackupFileSystem() {
          @Override
          public List<String> list(File path) throws IOException {
            if (path.equals(home)) throw new IOException("PARENT_LINK");
            return super.list(path);
          }
        };
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                new MaintenanceDataSnapshot(
                        guarded, files, task.directory(), new File(files, "public"))
                    .capture("/root/project", "test", new BackupControl(null)));
    assertEquals("REBUILD_CAPTURE_PARENT_LINK", error.getMessage());
    assertEquals("PARENT_LINK", error.getCause().getMessage());
    assertEquals("conversation", Files.readString(new File(home, ".dsh/sessions/a").toPath()));
    assertFalse(new File(task.directory(), "previous-linux").exists());
  }

  @Test
  public void restoreRejectsLinkedParentAndRollbackRetainsOriginal() throws Exception {
    File files = temp.newFolder();
    fixture(files);
    var task = prepared(files);
    task.begin();
    File newRoot = new File(files, "linux/ubuntu"),
        linked = new File(newRoot, "root/.dsh/sessions");
    Files.createDirectories(newRoot.toPath());
    BackupFileSystem guarded =
        new JvmBackupFileSystem() {
          boolean replaced;

          @Override
          public void parents(File root, String relative) throws IOException {
            // 模拟逐段 NOFOLLOW 在恢复一个文件前发现父级被替换。
            if (relative.equals("root/.dsh/sessions/a")) replaced = true;
            super.parents(root, relative);
          }

          @Override
          public Node stat(File path) throws IOException {
            if (replaced && path.equals(linked))
              return new Node("LINK", "replacement", 0, 0, 0, 0777);
            return super.stat(path);
          }
        };
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                new MaintenanceDataSnapshot(
                        guarded, files, task.directory(), new File(files, "public"))
                    .restore(newRoot, new BackupControl(null)));
    assertEquals("REBUILD_RESTORE_PARENT_LINK", error.getMessage());
    assertEquals("PARENT_LINK", error.getCause().getMessage());
    assertFalse(new File(linked, "a").exists());
    task.rollback();
    assertEquals(
        "conversation", Files.readString(new File(newRoot, "root/.dsh/sessions/a").toPath()));
  }

  @Test
  public void staticVersionSuccessCannotCommitAndCommittedDataIsNeverRewound() throws Exception {
    File files = temp.newFolder();
    fixture(files);
    var task = prepared(files);
    task.begin();
    put(files, "linux/ubuntu/root/.dsh/sessions/a", "restored");
    var descriptor = ManagedRuntimeTransactionTest.descriptor('b');
    assertThrows(IOException.class, () -> task.commit(descriptor, Map.of("nativeModules", true)));
    task.dataRestored(hash(new File(task.directory(), "data.dshdata")));
    task.commit(descriptor, ManagedRuntimeTransactionTest.health(descriptor));
    put(files, "linux/ubuntu/root/.dsh/sessions/a", "new message after commit");
    task.rollback();
    assertEquals(
        "new message after commit",
        Files.readString(new File(files, "linux/ubuntu/root/.dsh/sessions/a").toPath()));
    assertTrue(new File(task.directory(), "previous-linux").isDirectory());
  }

  @Test
  public void realKillAfterMoveAndDuringRollbackConverges() throws Exception {
    File files = temp.newFolder();
    fixture(files);
    var task = prepared(files);
    kill(files, task.directory().getName(), "candidate-created", false);
    put(files, "linux/ubuntu/partial", "candidate");
    kill(files, task.directory().getName(), "rollback-old", true);
    put(files, "linux/ubuntu/root/new-file", "new data after original returned");
    EnvironmentRebuildTransaction.pending(FS, files).rollback();
    assertEquals(
        "new data after original returned",
        Files.readString(new File(files, "linux/ubuntu/root/new-file").toPath()));
    assertEquals(
        "conversation",
        Files.readString(new File(files, "linux/ubuntu/root/.dsh/sessions/a").toPath()));
  }

  @Test
  public void rebuildPreservesUserToolRootAndReinjectsManagedPaths() throws Exception {
    File files = temp.newFolder();
    fixture(files);
    var transaction = prepared(files);
    transaction.begin();
    // 新 rootfs 只带受管注入内容；用户工具目录应来自数据快照，/opt/gh 由受管资产负责。
    put(files, "linux/ubuntu/opt/gh/bin/gh", "new managed gh");
    new MaintenanceDataSnapshot(FS, files, transaction.directory(), new File(files, "public"))
        .restore(new File(files, "linux/ubuntu"), new BackupControl(null));
    assertEquals(
        "user installed tool",
        Files.readString(new File(files, "linux/ubuntu/opt/user-tool/bin/tool").toPath()));
    assertEquals(
        "new managed gh", Files.readString(new File(files, "linux/ubuntu/opt/gh/bin/gh").toPath()));
    assertFalse(new File(files, "linux/ubuntu/usr/local/bin/user-entry").exists());
  }

  @Test
  public void toolRootPreflightIsBoundedAndReportsTheOverflow() throws Exception {
    File files = temp.newFolder();
    fixture(files);
    File rootfs = new File(files, "linux/ubuntu");
    var usage = MaintenanceDataSnapshot.inspectToolRoots(FS, rootfs, 1000);
    assertFalse(usage.overflowed);
    assertTrue("expected /opt entries, got " + usage.entries, usage.entries >= 7);
    assertTrue(usage.bytes > 0);
    var limited = MaintenanceDataSnapshot.inspectToolRoots(FS, rootfs, 3);
    assertTrue(limited.overflowed);
    assertTrue(limited.entries > 3);
  }

  private void kill(File files, String id, String phase, boolean rollback) throws Exception {
    Set<String> classpath = new LinkedHashSet<>();
    for (Class<?> type :
        List.of(
            NativeEnvironmentRebuildTest.class,
            EnvironmentRebuildTransaction.class,
            com.google.gson.stream.JsonReader.class,
            org.junit.Assert.class))
      classpath.add(
          new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
    String binary =
        new File(
                System.getProperty("java.home"),
                "bin/"
                    + (System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"))
            .getPath();
    Process child =
        new ProcessBuilder(
                binary,
                "-cp",
                String.join(File.pathSeparator, classpath),
                EnvironmentCrashProcess.class.getName(),
                files.getPath(),
                id,
                phase,
                Boolean.toString(rollback))
            .redirectErrorStream(true)
            .redirectOutput(new File(files, "child-" + phase + ".log"))
            .start();
    File marker = new File(files, "reached-" + phase);
    long until = System.currentTimeMillis() + 15000;
    try {
      while (!marker.exists() && child.isAlive() && System.currentTimeMillis() < until)
        Thread.sleep(20);
      assertTrue("未到真实强杀边界：" + phase, marker.exists());
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }
}
