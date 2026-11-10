package com.deepseekharness.app.backup;

import static com.deepseekharness.app.backup.ConfigurationSnapshotsTest.dsh;
import static com.deepseekharness.app.backup.ConfigurationSnapshotsTest.engine;
import static com.deepseekharness.app.backup.ConfigurationSnapshotsTest.fixture;
import static com.deepseekharness.app.backup.ConfigurationSnapshotsTest.put;
import static com.deepseekharness.app.backup.ConfigurationSnapshotsTest.rows;
import static com.deepseekharness.app.backup.ConfigurationSnapshotsTest.untouched;
import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** 快照轮换、隔离与显式删除：任何坏快照都不能把写入口卡死，也不能被静默删除。 */
public class ConfigurationSnapshotsRetentionTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private static final BackupControl CONTROL = new BackupControl(null);

  static File store(File root) {
    return new File(root, ConfigurationSnapshots.STORE);
  }

  static List<String> snapshotDirs(File root) {
    String[] names = store(root).list();
    List<String> result = new ArrayList<>();
    if (names != null)
      for (String name : names)
        if (name.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)) result.add(name);
    Collections.sort(result);
    return result;
  }

  static List<String> children(File directory) {
    String[] names = directory.list();
    List<String> result = names == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(names));
    Collections.sort(result);
    return result;
  }

  static File retained(File root) {
    return new File(store(root), ConfigurationSnapshots.RETAINED);
  }

  static File retiring(File root) {
    return new File(store(root), ConfigurationSnapshots.RETIRING);
  }

  static String orphan(File root, boolean withMetadata) throws Exception {
    String id = UUID.randomUUID().toString();
    put(store(root), id + "/0", "partial body");
    if (withMetadata)
      put(store(root), id + "/metadata.json", "{\"version\":2,\"id\":\"" + id + "\"}");
    return id;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> retainedSummary(ConfigurationSnapshots engine) throws Exception {
    return (Map<String, Object>) engine.list(CONTROL).get("retained");
  }

  static Set<String> catalogIds(File root) throws Exception {
    Map<String, Object> catalog =
        BackupJson.read(Files.readAllBytes(new File(store(root), "catalog.json").toPath()), 4096);
    Set<String> ids = new HashSet<>();
    for (Object value : catalog.values()) ids.add((String) value);
    return ids;
  }

  @Test
  public void oversizedCaptureLeavesNoPartialDirectoryAndRecovers() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    var engine = engine(root, null);
    put(dsh(root), "profiles/web/pnpm-lock.yaml", "x".repeat(5 * 1024 * 1024));
    for (int i = 0; i < 3; i++) {
      IOException error = assertThrows(IOException.class, () -> engine.prepare("big", CONTROL));
      assertEquals("RECOVERY_SIZE", error.getMessage());
    }
    assertEquals(List.of(), snapshotDirs(root));
    assertEquals(List.of(), children(retiring(root)));
    put(dsh(root), "profiles/web/pnpm-lock.yaml", "small lock");
    engine.prepare("ok", CONTROL);
    engine.healthy("ok", CONTROL);
    assertEquals(1, snapshotDirs(root).size());
    untouched(root);
  }

  @Test
  public void cancelledCaptureLeavesNoPartialDirectory() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    var engine = engine(root, null);
    BackupControl cancelled = new BackupControl(null);
    cancelled.cancel();
    assertThrows(InterruptedIOException.class, () -> engine.prepare("cancelled", cancelled));
    assertEquals(List.of(), snapshotDirs(root));
  }

  @Test
  public void legacyOrphansAreQuarantinedNeverDeletedAndNoLongerBlockCapture() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    Set<String> orphans = new HashSet<>();
    for (int i = 0; i < 80; i++) orphans.add(orphan(root, false));
    var engine = engine(root, null);
    // 旧实现在这里报 RECOVERY_RETENTION_LIMIT，插件卸载前置的 before 同样失败。
    engine.prepare("after-orphans", CONTROL);
    assertEquals("before-1", engine.before(CONTROL));
    Set<String> survivors = new HashSet<>(children(retained(root)));
    survivors.addAll(snapshotDirs(root));
    assertTrue("孤儿目录不能被自动删除", survivors.containsAll(orphans));
    assertEquals(ConfigurationSnapshots.RETAINED_LIMIT, children(retained(root)).size());
    Map<String, Object> summary = retainedSummary(engine);
    assertEquals(ConfigurationSnapshots.RETAINED_LIMIT, summary.get("count"));
    assertEquals("incomplete", ((Map<?, ?>) ((List<?>) summary.get("items")).get(0)).get("reason"));
    // 用户清空隔离区后，剩余孤儿逐批进入隔离区，直至收敛。
    for (int round = 0; round < 3; round++) {
      engine.discardRetained("all", CONTROL);
      engine.before(CONTROL);
    }
    engine.discardRetained("all", CONTROL);
    assertEquals(catalogIds(root), new HashSet<>(snapshotDirs(root)));
    assertEquals(List.of(), children(retained(root)));
    untouched(root);
  }

  @Test
  public void damagedReferencedSlotIsReplacedFirstAndQuarantined() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    var engine = engine(root, null);
    engine.prepare("one", CONTROL);
    engine.healthy("one", CONTROL);
    String damaged = (String) rows(engine).get(0).get("id");
    put(store(root), damaged + "/4", "tampered");
    Map<String, Object> invalid = rows(engine).get(0);
    assertEquals(true, invalid.get("invalid"));
    assertEquals(damaged, invalid.get("id"));
    assertEquals("RECOVERY_CHECKSUM", invalid.get("error"));
    // 旧实现：healthy/before/create 全部报 RECOVERY_CHECKSUM。
    engine.prepare("two", CONTROL);
    engine.healthy("two", CONTROL);
    assertEquals("healthy-1", rows(engine).get(0).get("slot"));
    assertFalse(rows(engine).stream().anyMatch(row -> Boolean.TRUE.equals(row.get("invalid"))));
    assertEquals(List.of(damaged), children(retained(root)));
    assertEquals(
        "damaged",
        ((Map<?, ?>) ((List<?>) retainedSummary(engine).get("items")).get(0)).get("reason"));
    engine.create("settings.yaml", CONTROL);
    untouched(root);
  }

  @Test
  public void danglingSlotAfterManualDeletionNoLongerBlocksWrites() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    var engine = engine(root, null);
    engine.before(CONTROL);
    String id = snapshotDirs(root).get(0);
    new JvmBackupFileSystem().removeOwned(store(root), id);
    Map<String, Object> row = rows(engine).get(0);
    assertEquals(true, row.get("invalid"));
    assertEquals(id, row.get("id"));
    // 旧实现：before/create 报 UNSAFE_ROOT。
    engine.before(CONTROL);
    engine.create("settings.yaml", CONTROL);
    assertFalse(rows(engine).stream().anyMatch(r -> Boolean.TRUE.equals(r.get("invalid"))));
    assertEquals("{}\n", Files.readString(new File(dsh(root), "settings.yaml").toPath()));
    untouched(root);
  }

  @Test
  public void interruptedRetirementFinishesAndHalfDeletedOriginalIsQuarantined() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    String leftover = UUID.randomUUID().toString();
    put(retiring(root), leftover + "/0", "half removed");
    String halfDeleted = orphan(root, true);
    var engine = engine(root, null);
    engine.prepare("after-interruption", CONTROL);
    assertEquals(List.of(), children(retiring(root)));
    assertEquals(List.of(halfDeleted), children(retained(root)));
    assertEquals(1, snapshotDirs(root).size());
  }

  @Test
  public void removalFailureAfterCommitDoesNotFailTheOperation() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    JvmBackupFileSystem jvm = new JvmBackupFileSystem();
    boolean[] fail = {true};
    BackupFileSystem failing =
        (BackupFileSystem)
            java.lang.reflect.Proxy.newProxyInstance(
                BackupFileSystem.class.getClassLoader(),
                new Class[] {BackupFileSystem.class},
                (proxy, method, args) -> {
                  if (fail[0] && method.getName().equals("removeOwned"))
                    throw new IOException("RETIRED_DIRECTORY_NOT_OWNED");
                  try {
                    return method.invoke(jvm, args);
                  } catch (java.lang.reflect.InvocationTargetException error) {
                    throw error.getCause();
                  }
                });
    var engine = new ConfigurationSnapshots(failing, root, dsh(root));
    for (int i = 0; i < 5; i++) {
      put(dsh(root), "settings.yaml", "value-" + i);
      engine.before(CONTROL);
      Thread.sleep(2);
    }
    assertEquals(3, rows(engine).size());
    assertTrue(
        engine.drainWarnings().stream().anyMatch(w -> w.contains("RETIRED_DIRECTORY_NOT_OWNED")));
    assertFalse(children(retiring(root)).isEmpty());
    fail[0] = false;
    engine.before(CONTROL);
    assertEquals(List.of(), children(retiring(root)));
    assertEquals(catalogIds(root), new HashSet<>(snapshotDirs(root)));
  }

  @Test
  public void discardBindsSlotAndIdAndKeepsSharedReferences() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    var engine = engine(root, null);
    engine.prepare("shared", CONTROL);
    engine.healthy("shared", CONTROL);
    String shared = (String) rows(engine).get(0).get("id");
    put(dsh(root), "settings.yaml", "before value");
    engine.before(CONTROL);
    String before =
        (String)
            rows(engine).stream()
                .filter(row -> row.get("slot").equals("before-1"))
                .findFirst()
                .get()
                .get("id");
    assertEquals(
        "RECOVERY_CHANGED",
        assertThrows(IOException.class, () -> engine.discard("before-1", shared, null, CONTROL))
            .getMessage());
    assertEquals(
        "RECOVERY_SLOT",
        assertThrows(IOException.class, () -> engine.discard("candidate", shared, null, CONTROL))
            .getMessage());
    assertEquals("RECOVERY_DISCARDED", engine.discard("before-1", before, null, CONTROL));
    assertFalse(new File(store(root), before).exists());
    // 健康槽位与启动候选指向同一份：移除列表项，但正文仍由候选持有。
    assertEquals("RECOVERY_DISCARDED", engine.discard("healthy-1", shared, null, CONTROL));
    assertTrue(new File(store(root), shared).isDirectory());
    assertTrue(rows(engine).isEmpty());
    assertEquals("before value", Files.readString(new File(dsh(root), "settings.yaml").toPath()));
    untouched(root);
  }

  @Test
  public void damagedSnapshotCanBeDiscardedExplicitly() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    var engine = engine(root, null);
    engine.before(CONTROL);
    String id = snapshotDirs(root).get(0);
    put(store(root), id + "/4", "tampered");
    assertEquals("RECOVERY_DISCARDED", engine.discard("before-1", id, null, CONTROL));
    assertTrue(rows(engine).isEmpty());
    assertEquals(List.of(), snapshotDirs(root));
    assertEquals(List.of(), children(retained(root)));
  }

  @Test
  public void legacyCheckpointDiscardRequiresTheShownFingerprint() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    ConfigurationSnapshotsTest.legacy(
        root, "healthy-1", "legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var engine = engine(root, null);
    Map<String, Object> row = rows(engine).get(0);
    assertEquals("legacy-healthy-1", row.get("slot"));
    String fingerprint = (String) row.get("fingerprint");
    assertNotNull(fingerprint);
    assertThrows(
        IOException.class, () -> engine.discard("legacy-healthy-1", null, "stale", CONTROL));
    assertEquals(
        "RECOVERY_DISCARDED", engine.discard("legacy-healthy-1", null, fingerprint, CONTROL));
    assertFalse(new File(dsh(root), "dsha-startup-checkpoints/healthy-1.json").exists());
    assertTrue(rows(engine).isEmpty());
    untouched(root);
  }

  @Test
  public void retainedItemsCanBeDeletedOneByOneOrAll() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    String first = orphan(root, false), second = orphan(root, true), third = orphan(root, false);
    var engine = engine(root, null);
    engine.before(CONTROL);
    assertEquals(3, retainedSummary(engine).get("count"));
    assertEquals(1, engine.discardRetained(second, CONTROL));
    assertEquals(
        "RECOVERY_CHANGED",
        assertThrows(IOException.class, () -> engine.discardRetained(second, CONTROL))
            .getMessage());
    assertThrows(IOException.class, () -> engine.discardRetained("../x", CONTROL));
    assertEquals(2, engine.discardRetained("all", CONTROL));
    assertEquals(0, retainedSummary(engine).get("count"));
    assertFalse(new File(retained(root), first).exists());
    assertFalse(new File(retained(root), third).exists());
    assertEquals(0, engine.discardRetained("all", CONTROL));
  }

  @Test
  public void staleStateMarkersAreBounded() throws Exception {
    File root = temp.newFolder();
    for (int i = 0; i < ConfigurationSnapshots.MARKER_KEEP + 4; i++) {
      put(root, "startup-config-repair.pending", "marker-" + i);
      assertEquals("RECOVERY_MARKER_RETAINED", engine(root, null).recover(CONTROL));
    }
    assertEquals(
        ConfigurationSnapshots.MARKER_KEEP,
        children(new File(store(root), ConfigurationSnapshots.MARKERS)).size());
  }

  @Test
  public void damagedCatalogFallsBackOnlyForReadingAndBothDamagedFailsClosed() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    var engine = engine(root, null);
    engine.before(CONTROL);
    File catalog = new File(store(root), "catalog.json"),
        previous = new File(store(root), "catalog.json.previous");
    Files.copy(catalog.toPath(), previous.toPath());
    Files.writeString(catalog.toPath(), "{broken");
    assertEquals(1, rows(engine).size());
    assertThrows(IOException.class, () -> engine.before(CONTROL));
    assertEquals(1, rows(engine).size());
    assertTrue(previous.exists());
    // 两份都损坏：引用关系未知，不整理任何目录，写入明确失败。
    String unrelated = orphan(root, false);
    Files.writeString(catalog.toPath(), "{broken");
    Files.writeString(previous.toPath(), "{broken");
    assertThrows(IOException.class, () -> engine.before(CONTROL));
    assertTrue(new File(store(root), unrelated).isDirectory());
    assertEquals(2, snapshotDirs(root).size()); // 已提交快照 + 未知孤儿，没有新建目录。
  }

  @Test
  public void stalePreviousNeverDropsNewerReferencesOrChangesOriginals() throws Exception {
    // 当前目录损坏或丢失时，previous 可供查看，但可能遗漏最后一次已提交的快照。
    for (boolean missing : List.of(false, true)) {
      File root = temp.newFolder();
      fixture(root);
      var engine = engine(root, null);
      engine.prepare("startup", CONTROL);
      engine.healthy("startup", CONTROL);
      Map<String, Object> oldSnapshot = rows(engine).get(0);
      File catalog = new File(store(root), "catalog.json"),
          previous = new File(store(root), "catalog.json.previous");
      byte[] olderCatalog = Files.readAllBytes(catalog.toPath());
      engine.before(CONTROL);
      assertEquals(2, snapshotDirs(root).size());
      Files.write(previous.toPath(), olderCatalog);
      if (missing) Files.delete(catalog.toPath());
      else Files.writeString(catalog.toPath(), "{broken");
      var fs = new JvmBackupFileSystem();
      String originals = BackupTree.digest(fs, root, CONTROL);
      assertEquals(1, rows(engine).size());
      String slot = (String) oldSnapshot.get("slot"), id = (String) oldSnapshot.get("id");
      assertThrows(IOException.class, () -> engine.before(CONTROL));
      assertThrows(IOException.class, () -> engine.prepare("next", CONTROL));
      assertThrows(IOException.class, () -> engine.healthy("startup", CONTROL));
      assertThrows(IOException.class, () -> engine.create("settings.yaml", CONTROL));
      assertThrows(IOException.class, () -> engine.restore(slot, id, CONTROL));
      assertThrows(IOException.class, () -> engine.discard(slot, id, null, CONTROL));
      assertEquals(2, snapshotDirs(root).size());
      assertArrayEquals(olderCatalog, Files.readAllBytes(previous.toPath()));
      assertEquals(originals, BackupTree.digest(fs, root, CONTROL));
      untouched(root);
    }
  }

  @Test
  public void restoreSourceStaysPinnedWhileOrphansAreTidied() throws Exception {
    File root = temp.newFolder();
    fixture(root);
    var engine = engine(root, null);
    Map<String, Object> oldest = null;
    for (int i = 0; i < 3; i++) {
      put(dsh(root), "settings.yaml", "before-" + i);
      engine.before(CONTROL);
      if (i == 0) oldest = rows(engine).get(0);
      Thread.sleep(2);
    }
    for (int i = 0; i < 5; i++) orphan(root, false);
    put(dsh(root), "settings.yaml", "current");
    engine.restore((String) oldest.get("slot"), (String) oldest.get("id"), CONTROL);
    assertEquals("before-0", Files.readString(new File(dsh(root), "settings.yaml").toPath()));
    assertEquals(5, children(retained(root)).size());
    untouched(root);
  }

  @Test
  public void errorCodeOnlyExposesControlledCodes() {
    assertEquals(
        "RECOVERY_SIZE", ConfigurationSnapshots.errorCode(new IOException("RECOVERY_SIZE")));
    assertEquals(
        "IOException",
        ConfigurationSnapshots.errorCode(new IOException("/data/user/0/secret path")));
    assertEquals("UNKNOWN", ConfigurationSnapshots.errorCode(null));
  }
}
