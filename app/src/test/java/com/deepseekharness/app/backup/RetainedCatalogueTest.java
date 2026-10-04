package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class RetainedCatalogueTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  private File put(String path, String text) throws Exception {
    File file = new File(temp.getRoot(), path);
    Files.createDirectories(file.getParentFile().toPath());
    Files.writeString(file.toPath(), text);
    return file;
  }

  @Test
  public void oldTreeCanBeReadWithoutBashAndDoesNotReadCurrentData() throws Exception {
    String id = UUID.randomUUID().toString(),
        prefix = EnvironmentRebuildTransaction.HOME + "/" + id;
    File original =
        put(prefix + "/previous-linux/ubuntu/root/.dsh/sessions/message", "old conversation");
    put("linux/ubuntu/root/.dsh/sessions/message", "new conversation");
    put(prefix + "/committed", id + "\ncommitted\n");
    var catalogue =
        new RetainedCatalogue(
            fs, temp.getRoot(), new File(temp.getRoot(), "linux/ubuntu/root/.dsh"));
    var entry = catalogue.resolve("ENVIRONMENT:" + id + ":previous-linux-data");
    var roots = catalogue.sources(entry);
    assertEquals(1, roots.size());
    List<String> contents = new ArrayList<>();
    roots
        .get(0)
        .walk(
            item -> {
              if (item.kind.equals("FILE"))
                try (InputStream input = roots.get(0).open(item)) {
                  contents.add(
                      new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            },
            new BackupControl(null));
    assertEquals(List.of("old conversation"), contents);
    assertEquals("old conversation", Files.readString(original.toPath()));
    assertEquals(
        "new conversation",
        Files.readString(
            new File(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/message").toPath()));
  }

  @Test
  public void unknownRecordDoesNotHideReadableOldTree() throws Exception {
    String id = UUID.randomUUID().toString();
    put("host-backup-operations/unknown-user-folder/notes", "only original");
    put(
        EnvironmentRebuildTransaction.HOME
            + "/"
            + id
            + "/previous-linux/ubuntu/root/.dsh/settings.yaml",
        "owned: true");
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), new File(temp.getRoot(), "home"));
    assertTrue(catalog.list().stream().anyMatch(entry -> entry.status.equals("UNRECOGNIZED")));
    assertEquals(
        "application", catalog.resolve("ENVIRONMENT:" + id + ":previous-linux-data").scope);
    assertThrows(
        IOException.class, () -> catalog.resolve("ENVIRONMENT:../../outside:previous-linux-data"));
    assertEquals(
        "only original",
        Files.readString(
            new File(temp.getRoot(), "host-backup-operations/unknown-user-folder/notes").toPath()));
  }

  @Test
  public void corruptedMarkerRemainsUnknownAndCannotAuthorizeRestore() throws Exception {
    String id = UUID.randomUUID().toString();
    String base = EnvironmentRebuildTransaction.HOME + "/" + id;
    put(base + "/previous-linux/ubuntu/root/.dsh/sessions/message", "retained");
    put(base + "/committed", "wrong owner");
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), new File(temp.getRoot(), "home"));
    assertEquals("UNREADABLE", catalog.list().get(0).status);
    assertThrows(
        IOException.class, () -> catalog.resolve("ENVIRONMENT:" + id + ":previous-linux-data"));
  }

  @Test
  public void selectedPersonalOriginalRestoresAsASeparateProjectRoot() throws Exception {
    String id = UUID.randomUUID().toString();
    put(
        ManagedRuntimeTransaction.HOME + "/" + id + "/previous/runtime-0/my-notes",
        "unique modified dependency");
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), new File(temp.getRoot(), "home"));
    var source = catalog.sources(catalog.resolve("RUNTIME:" + id + ":previous")).get(0);
    assertEquals("projects", source.scope());
    assertEquals("project", source.description().get("logicalKind"));
    assertTrue(source.id().startsWith("project-"));
  }

  @Test
  public void settingsOnlyRestoreHasInspectableExportableEntriesForEveryProfile() throws Exception {
    String id = UUID.randomUUID().toString();
    put(
        "plugin-imports/" + id + "/settings/profiles/web/cordis.patch.yml",
        "- id: llm\n  config: {model: saved}\n");
    put(
        "plugin-imports/" + id + "/settings/profiles/team/package.json",
        "{\"dsh\":{\"profile\":{}}}");
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), new File(temp.getRoot(), "home"));
    var entries = catalog.list();
    assertEquals(2, entries.size());
    for (var entry : entries) {
      assertEquals(RetainedCatalogue.Kind.SETTINGS, entry.kind);
      assertNotNull(entry.source);
      var sources = catalog.sources(entry);
      assertEquals(1, sources.size());
      assertEquals("settings", sources.get(0).scope());
      assertEquals("dsh-profile-config", sources.get(0).description().get("logicalKind"));
      assertTrue(
          com.deepseekharness.app.util.ProfileConfigPath.accepts(
              (String) sources.get(0).description().get("name")));
    }
  }

  @Test
  public void migratedAndRestoredLegacyPresetsRemainAccessibleWithoutActivation() throws Exception {
    put(
        "home/.dsha-rc1-migration/legacy-agent-presets/crew/0123456789abcdef/bundle/package.json",
        "{\"name\":\"dsha-legacy-preset-crew\"}");
    String id = UUID.randomUUID().toString();
    put(
        "plugin-imports/" + id + "/declarations/.agent-presets/old/agent.cordis.yml",
        "- id: test\n");
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), new File(temp.getRoot(), "home"));
    var entries =
        catalog.list().stream().filter(e -> e.kind == RetainedCatalogue.Kind.PRESET).toList();
    assertEquals(2, entries.size());
    for (var entry : entries) {
      assertEquals(RetainedCatalogue.Kind.PRESET, entry.kind);
      assertNotNull(catalog.resolve(entry.key()).source);
      assertEquals("projects", catalog.sources(entry).get(0).scope());
    }
    assertTrue(entries.stream().anyMatch(e -> e.status.equals("CONVERSION_REQUIRED")));
    assertTrue(entries.stream().anyMatch(e -> e.status.equals("QUARANTINED")));
  }

  @Test
  public void currentGenerationPresetNamesStayVisibleAndLegacyHexNamesStillRead() throws Exception {
    put(
        "home/.dsha-rc1-migration/legacy-agent-presets/crew/0123456789abcdef-89abcdef/raw/agent.cordis.yml",
        "- id: current\n");
    put(
        "home/.dsha-rc1-migration/legacy-agent-presets/crew/1123456789abcdef/raw/agent.cordis.yml",
        "- id: historical\n");
    put(
        "home/.dsha-rc1-migration/legacy-agent-presets/crew/0123456789abcdef-INVALID/raw/agent.cordis.yml",
        "- id: unknown\n");
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), new File(temp.getRoot(), "home"));
    var presets =
        catalog.list().stream().filter(e -> e.kind == RetainedCatalogue.Kind.PRESET).toList();
    assertEquals(2, presets.size());
    for (var entry : presets) {
      assertEquals("CONVERSION_REQUIRED", entry.status);
      assertEquals(entry.source, catalog.resolve(entry.key()).source);
      assertEquals("projects", catalog.sources(entry).get(0).scope());
    }
    assertTrue(
        new File(
                temp.getRoot(),
                "home/.dsha-rc1-migration/legacy-agent-presets/crew/0123456789abcdef-INVALID/raw/agent.cordis.yml")
            .isFile());
  }

  @Test
  public void
      retainedApplicationExportOmitsMachineHeadersAndLegacyNativeKeyWithoutEditingOriginals()
          throws Exception {
    String id = UUID.randomUUID().toString();
    String base =
        EnvironmentRebuildTransaction.HOME + "/" + id + "/previous-linux/ubuntu/root/.dsh";
    File key = put(base + "/.dsha-apikey", "historical native key");
    File header = put(base + "/profiles/.bridge_headers", "X-Token: machine fixture");
    put(base + "/profiles/web/package.json", "{\"name\":\"profile\"}");
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), new File(temp.getRoot(), "home"));
    var roots = catalog.sources(catalog.resolve("ENVIRONMENT:" + id + ":previous-linux-data"));
    assertEquals(1, roots.size());
    List<String> payload = new ArrayList<>(), excluded = new ArrayList<>();
    roots
        .get(0)
        .walk(
            item -> {
              if (item.kind.equals("FILE")) payload.add(item.path);
              if (item.kind.equals("EXCLUDED")) excluded.add(item.path);
            },
            new BackupControl(null));
    assertEquals(List.of("web/package.json"), payload);
    assertEquals(List.of(".bridge_headers"), excluded);
    assertEquals("historical native key", Files.readString(key.toPath()));
    assertEquals("X-Token: machine fixture", Files.readString(header.toPath()));
  }

  @Test
  public void rc1MigrationRecordsAreVisibleAndExportable() throws Exception {
    put(
        "rc1-migration-state/current.json",
        "{\"version\":2,\"generation\":\"01234567-89ab-cdef-0123-456789abcdef\",\"status\":\"pending\"}");
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), new File(temp.getRoot(), "home"));
    var entry =
        catalog.list().stream()
            .filter(e -> e.kind == RetainedCatalogue.Kind.MIGRATION)
            .findFirst()
            .orElseThrow();
    assertEquals("PENDING_RETRY", entry.status);
    assertNotNull(entry.source);
    assertEquals("settings", catalog.sources(entry).get(0).scope());
  }

  @Test
  public void completedBackupHistoryStaysVisibleInRetainedCatalogue() throws Exception {
    File files = temp.getRoot(), operations = HostOperationArchive.reserve(fs, files);
    String id = UUID.randomUUID().toString();
    File operation = new File(operations, id);
    fs.directory(operation);
    byte[] artifact = new byte[80];
    System.arraycopy(PortableBackupCrypto.MAGIC, 0, artifact, 0, PortableBackupCrypto.MAGIC.length);
    File archive = new File(operation, "portable.dshbak");
    Files.write(archive.toPath(), artifact);
    String hash = BackupArchive.hex(BackupArchive.sha().digest(artifact));
    fs.atomic(
        operation,
        "verified.json",
        BackupJson.write(
            Map.of(
                "encryptedSha256",
                hash,
                "encryptedBytes",
                (long) artifact.length,
                "entries",
                0L,
                "integrity",
                "QUIESCENT",
                "requestedScope",
                "application",
                "createdAt",
                9L),
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
                9L,
                "result",
                "COMPLETE",
                "error",
                "",
                "artifact",
                "portable.dshbak"),
            16384));
    HostOperationArchive.archiveIfTerminal(fs, files, operation);
    operation = HostOperationArchive.locate(fs, operations, id);
    archive = new File(operation, "portable.dshbak");
    var catalog = new RetainedCatalogue(fs, files, new File(files, "home"));
    var entry =
        catalog.list().stream()
            .filter(e -> e.kind == RetainedCatalogue.Kind.BACKUP && e.id.equals(id))
            .findFirst()
            .orElseThrow();
    assertEquals("encrypted", entry.part);
    assertEquals(archive.getAbsolutePath(), entry.source.getAbsolutePath());
    VerifiedBackupCopy.inspect(fs, operations, id).verify(fs, new BackupControl(null));
  }

  @Test
  public void duplicateVerifiedBackupDirectoriesRemainVisibleAndUnactionable() throws Exception {
    String id = UUID.randomUUID().toString();
    put("host-backup-operations/" + id + "/verified.json", "untrusted original");
    put("host-backup-operations/completed/" + id + "/verified.json", "retained original");
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), new File(temp.getRoot(), "home"));
    var rows =
        catalog.list().stream()
            .filter(entry -> entry.kind == RetainedCatalogue.Kind.BACKUP && entry.id.equals(id))
            .toList();
    assertEquals(2, rows.size());
    assertTrue(
        rows.stream().allMatch(entry -> entry.status.equals("DUPLICATE") && entry.source == null));
    assertNotEquals(
        rows.get(0).directory.getAbsolutePath(), rows.get(1).directory.getAbsolutePath());
    assertEquals(
        "RETAINED_SOURCE_DUPLICATE",
        catalog
            .resolveAll(Set.of("BACKUP:" + id + ":encrypted"))
            .errors
            .get("BACKUP:" + id + ":encrypted"));
  }

  @Test
  public void completedPluginHistoryBeyond256HasEveryPageAndDeletionOriginal() throws Exception {
    File home = new File(temp.getRoot(), "home");
    Set<String> expected = new HashSet<>();
    for (int index = 0; index < 257; index++) {
      String id = UUID.randomUUID().toString();
      expected.add("PLUGIN:" + id + ":delete-source");
      put(
          "home/plugin-install-operations/completed/" + id + "/delete-source/index.js",
          "old plugin " + index);
      put("home/plugin-install-operations/completed/" + id + "/committed", id + "\ncommitted\n");
    }
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), home);
    Set<String> actual = new HashSet<>();
    List<String> pagedOrder = new ArrayList<>();
    RetainedCatalogue.Cursor cursor = null;
    for (int index = 0; index < 6; index++) {
      var page = catalog.page(cursor, 50);
      assertEquals(257, page.total);
      assertTrue(page.entries.size() <= 50);
      for (var entry : page.entries) {
        actual.add(entry.key());
        pagedOrder.add(entry.key());
      }
      assertEquals(index < 5, page.hasNext());
      cursor = page.next;
    }
    assertEquals(expected, actual);
    assertEquals(catalog.list().stream().map(RetainedCatalogue.Entry::key).toList(), pagedOrder);
    var recovered = catalog.resolve(expected.iterator().next());
    assertNotNull(recovered.source);
    assertThrows(IOException.class, () -> catalog.page(null, 101));
    assertFalse(new RetainedCatalogue.Page(List.of(), Integer.MAX_VALUE, 100, null).hasNext());
  }

  @Test
  public void cursorDetectsVisibleHistoryChangeAndKeepsOnlyRequestedPage() throws Exception {
    File home = new File(temp.getRoot(), "home");
    for (int index = 0; index < 120; index++) {
      String id = UUID.randomUUID().toString();
      put(
          "home/plugin-install-operations/completed/" + id + "/delete-source/index.js",
          "version " + index);
    }
    var catalog = new RetainedCatalogue(fs, temp.getRoot(), home);
    var first = catalog.page(null, 25);
    assertEquals(120, first.total);
    assertEquals(25, first.entries.size());
    assertNotNull(first.next);
    var second = catalog.page(first.next, 25);
    assertEquals(25, second.entries.size());
    String changed = UUID.randomUUID().toString();
    put(
        "home/plugin-install-operations/completed/" + changed + "/delete-source/index.js",
        "new record");
    IOException error = assertThrows(IOException.class, () -> catalog.page(first.next, 25));
    assertEquals("RETAINED_PAGE_CHANGED", error.getMessage());
    assertEquals(121, catalog.page(null, 25).total);
  }

  @Test
  public void thousandHistoryBatchLookupScansOnceAndReportsPerKeyProblems() throws Exception {
    File home = new File(temp.getRoot(), "home");
    List<String> chosen = new ArrayList<>();
    for (int index = 0; index < 1000; index++) {
      String id = UUID.randomUUID().toString();
      put(
          "home/plugin-install-operations/completed/" + id + "/delete-source/index.js",
          "old " + index);
      if (index < 10) chosen.add("PLUGIN:" + id + ":delete-source");
    }
    JvmBackupFileSystem delegate = new JvmBackupFileSystem();
    int[] calls = {0};
    BackupFileSystem counted =
        (BackupFileSystem)
            java.lang.reflect.Proxy.newProxyInstance(
                BackupFileSystem.class.getClassLoader(),
                new Class[] {BackupFileSystem.class},
                (proxy, method, args) -> {
                  calls[0]++;
                  try {
                    return method.invoke(delegate, args);
                  } catch (java.lang.reflect.InvocationTargetException error) {
                    throw error.getCause();
                  }
                });
    var catalog = new RetainedCatalogue(counted, temp.getRoot(), home);
    Set<String> selected = new LinkedHashSet<>(chosen);
    String missing = "PLUGIN:" + UUID.randomUUID() + ":delete-source";
    selected.add(missing);
    var batch = catalog.resolveAll(selected);
    int batchCalls = calls[0];
    assertEquals(10, batch.entries.size());
    assertEquals("RETAINED_SOURCE_MISSING", batch.errors.get(missing));
    calls[0] = 0;
    for (String key : chosen) assertNotNull(catalog.resolve(key));
    int repeatedCalls = calls[0];
    assertTrue("one batch should avoid a scan per selected record", repeatedCalls > batchCalls * 5);
    System.out.println(
        "RETAINED_BATCH_1000 selected="
            + selected.size()
            + " fsCalls="
            + batchCalls
            + " repeated="
            + repeatedCalls);
    String duplicate = chosen.get(0), id = duplicate.split(":")[1];
    put("home/plugin-install-operations/" + id + "/delete-source/index.js", "duplicate");
    assertEquals(
        "RETAINED_SOURCE_DUPLICATE", catalog.resolveAll(Set.of(duplicate)).errors.get(duplicate));
    var two =
        catalog.list().stream()
            .filter(entry -> entry.kind == RetainedCatalogue.Kind.PLUGIN && entry.id.equals(id))
            .toList();
    assertEquals(2, two.size());
    assertTrue(two.stream().allMatch(entry -> entry.status.equals("DUPLICATE")));
    assertNotEquals(two.get(0).directory.getAbsolutePath(), two.get(1).directory.getAbsolutePath());
    String changed = chosen.get(1);
    String changedId = changed.split(":")[1];
    java.nio.file.Path original =
        new File(
                home,
                "plugin-install-operations/completed/" + changedId + "/delete-source/index.js")
            .toPath();
    Files.delete(original);
    Files.delete(original.getParent());
    assertEquals(
        "RETAINED_SOURCE_CHANGED", catalog.resolveAll(Set.of(changed)).errors.get(changed));
  }
}
