package com.deepseekharness.app.util;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

public class StartupHistoryStoreTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void rotatesFiveRecordsKeepsFailuresAndBothLanguages() throws Exception {
    StartupHistoryStore store =
        new StartupHistoryStore(
            new com.deepseekharness.app.backup.JvmBackupFileSystem(), folder.getRoot());
    for (int i = 0; i < 7; i++) {
      StartupTrace trace = new StartupTrace();
      trace.begin(i + 1, 0, false);
      trace.stage(i + 1, 1, "等待鉴权链接");
      store.save(
          UUID.randomUUID().toString(),
          100 + i,
          i == 6 ? "failed" : "ready",
          "token=secret-value",
          trace.snapshot(3, "zh"),
          trace.snapshot(3, "en"));
    }
    List<StartupHistoryStore.Entry> entries =
        new StartupHistoryStore(
                new com.deepseekharness.app.backup.JvmBackupFileSystem(), folder.getRoot())
            .list("en");
    assertEquals(5, entries.size());
    assertEquals(106, entries.get(0).started);
    assertEquals("failed", entries.get(0).status);
    assertFalse(entries.get(0).log.contains("等待"));
    assertTrue(store.list("zh").get(0).log.contains("等待"));
    try (java.util.stream.Stream<java.nio.file.Path> files =
        java.nio.file.Files.walk(folder.getRoot().toPath())) {
      for (java.nio.file.Path path :
          (Iterable<java.nio.file.Path>) files.filter(java.nio.file.Files::isRegularFile)::iterator)
        assertFalse(
            "secret reached durable history: " + path,
            java.nio.file.Files.readString(path).contains("secret-value"));
    }
  }

  @Test
  public void invalidRecordIdCannotEscapeDirectory() throws Exception {
    StartupHistoryStore store =
        new StartupHistoryStore(
            new com.deepseekharness.app.backup.JvmBackupFileSystem(), folder.getRoot());
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    try {
      store.save("../escape", 0, "failed", "", trace.snapshot(1), trace.snapshot(1));
      fail();
    } catch (java.io.IOException expected) {
    }
    assertTrue(store.list("zh").isEmpty());
  }

  @Test
  public void trustedParentAliasIsNormalizedBeforeCheckingRecordPaths() throws Exception {
    folder.newFolder("alias");
    StartupHistoryStore store =
        new StartupHistoryStore(
            new com.deepseekharness.app.backup.JvmBackupFileSystem(),
            new java.io.File(folder.getRoot(), "alias/.."));
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    store.save(
        UUID.randomUUID().toString(),
        1,
        "ready",
        "",
        trace.snapshot(1, "zh"),
        trace.snapshot(1, "en"));
    assertEquals(
        1,
        new StartupHistoryStore(
                new com.deepseekharness.app.backup.JvmBackupFileSystem(), folder.getRoot())
            .list("en")
            .size());
  }

  @Test
  public void interruptedReplacementKeepsThePriorRecordReadableAndTheNextWriteCanRecover()
      throws Exception {
    boolean[] failPublication = {false};
    int[] syncs = {0};
    var fs =
        new com.deepseekharness.app.backup.JvmBackupFileSystem() {
          @Override
          public void move(java.io.File source, java.io.File target) throws java.io.IOException {
            if (failPublication[0] && source.getName().contains(".tmp-"))
              throw new java.io.IOException("PUBLICATION_INTERRUPTED");
            super.move(source, target);
          }

          @Override
          public void syncDirectory(java.io.File directory) throws java.io.IOException {
            syncs[0]++;
            super.syncDirectory(directory);
          }
        };
    var store = new StartupHistoryStore(fs, folder.getRoot());
    var trace = new StartupTrace();
    trace.begin(1, 0, false);
    String id = UUID.randomUUID().toString();
    store.save(id, 1, "failed", "old", trace.snapshot(1, "zh"), trace.snapshot(1, "en"));
    failPublication[0] = true;
    assertThrows(
        java.io.IOException.class,
        () -> store.save(id, 2, "ready", "new", trace.snapshot(2, "zh"), trace.snapshot(2, "en")));
    assertEquals("failed", store.list("en").get(0).status);
    assertEquals(1, store.list("en").get(0).started);
    failPublication[0] = false;
    store.save(id, 3, "ready", "new", trace.snapshot(3, "zh"), trace.snapshot(3, "en"));
    assertEquals("ready", store.list("en").get(0).status);
    assertEquals(3, store.list("en").get(0).started);
    assertTrue(syncs[0] > 0);
  }
}
