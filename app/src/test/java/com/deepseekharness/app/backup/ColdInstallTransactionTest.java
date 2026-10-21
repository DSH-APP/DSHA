package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.ColdInstallPackages;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class ColdInstallTransactionTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  // Windows BasicFileAttributes.fileKey is null. Model directory birth identities explicitly;
  // real Android/Linux inode binding is covered separately by the parent-link syscall fixture.
  final BackupFileSystem fs =
      new BackupFileSystem() {
        final JvmBackupFileSystem io = new JvmBackupFileSystem();
        final Map<String, String> keys = new HashMap<>();

        public Node stat(File f) throws IOException {
          Node n = io.stat(f);
          return new Node(
              n.type,
              n.type.equals("MISSING")
                  ? ""
                  : keys.computeIfAbsent(f.getAbsolutePath(), k -> UUID.randomUUID().toString()),
              n.size,
              n.modified,
              n.device,
              n.mode);
        }

        public String readLink(File f) throws IOException {
          return io.readLink(f);
        }

        public List<String> list(File f) throws IOException {
          return io.list(f);
        }

        public InputStream read(File f, Node expected) throws IOException {
          if (!expected.same(stat(f))) throw new IOException("SOURCE_CHANGED");
          return io.read(f, io.stat(f));
        }

        public OutputStream create(File f) throws IOException {
          return io.create(f);
        }

        public void directory(File f) throws IOException {
          io.directory(f);
          keys.remove(f.getAbsolutePath());
        }

        public void move(File a, File b) throws IOException {
          io.move(a, b);
          var copy = new HashMap<>(keys);
          for (var e : copy.entrySet())
            if (e.getKey().equals(a.getAbsolutePath())
                || e.getKey().startsWith(a.getAbsolutePath() + File.separator)) {
              keys.remove(e.getKey());
              keys.put(
                  b.getAbsolutePath() + e.getKey().substring(a.getAbsolutePath().length()),
                  e.getValue());
            }
        }

        public void delete(File f) throws IOException {
          io.delete(f);
          keys.remove(f.getAbsolutePath());
        }

        public void mode(File f, int m) throws IOException {
          io.mode(f, m);
        }

        public void symlink(String t, File f) throws IOException {
          io.symlink(t, f);
        }

        public void syncDirectory(File f) throws IOException {
          io.syncDirectory(f);
        }
      };
  File files;
  ColdInstallPackages.HostRoot host;

  @Before
  public void init() throws Exception {
    File app = temporary.newFolder();
    files = new File(app, "files");
    fs.directory(files);
    host = ColdInstallPackages.bindPrivateFiles(fs, app, files);
  }

  ColdInstallTransaction create(ColdInstallTransaction.Fault fault) throws Exception {
    return ColdInstallTransaction.create(fs, host, "1".repeat(64), fault);
  }

  void data(ColdInstallTransaction tx) throws Exception {
    File bin = new File(tx.candidate().root(), "bin");
    fs.directory(bin);
    Files.writeString(new File(bin, "bash").toPath(), "candidate executable");
  }

  @Test
  public void existingDataRejectsBeforeAnyCandidateWrite() throws Exception {
    Files.createDirectories(new File(files, "linux/ubuntu/root").toPath());
    Files.writeString(new File(files, "linux/ubuntu/root/chat").toPath(), "keep");
    assertThrows(IOException.class, () -> create(null));
    assertEquals("keep", Files.readString(new File(files, "linux/ubuntu/root/chat").toPath()));
    assertFalse(new File(files, ColdInstallTransaction.HOME).exists());
  }

  @Test
  public void emptyRootfsWithStablePersonalDataMustUseProtectionTransaction() throws Exception {
    Files.createDirectories(new File(files, "user-data-v5/dsh/sessions").toPath());
    File original = new File(files, "user-data-v5/dsh/sessions/chat");
    Files.writeString(original.toPath(), "original chat");
    assertEquals(
        "COLD_EXISTING_DATA_DOMAIN",
        assertThrows(IOException.class, () -> create(null)).getMessage());
    assertEquals("original chat", Files.readString(original.toPath()));
    assertFalse(new File(files, "linux").exists());
  }

  @Test
  public void preparationAndUnknownGuestNeverPublish() throws Exception {
    var tx = create(null);
    data(tx);
    assertThrows(
        IOException.class,
        () ->
            tx.prepared(
                () -> {
                  throw new IOException("unknown guest");
                }));
    assertFalse(new File(files, "linux").exists());
    assertTrue(new File(tx.candidate().root(), "bin/bash").isFile());
  }

  @Test
  public void modePersistenceFailureRollsBackAndNeverPublishesReadyMarker() throws Exception {
    Files.createDirectories(new File(files, "linux/ubuntu").toPath());
    var original = fs.stat(new File(files, "linux"));
    var tx = create(null);
    data(tx);
    tx.prepared(() -> {});
    assertThrows(
        IOException.class,
        () ->
            tx.publish(
                () -> {},
                () -> {
                  throw new IOException("prefs failed");
                },
                () ->
                    Files.writeString(
                        new File(files, "linux/.offline-extracted").toPath(), "ready")));
    assertEquals(original.key, fs.stat(new File(files, "linux")).key);
    assertFalse(new File(files, "linux/.offline-extracted").exists());
    assertTrue(new File(tx.directory(), "failed-linux/ubuntu/bin/bash").isFile());
    assertTrue(ColdInstallTransaction.pending(fs, files).isEmpty());
  }

  @Test
  public void everyPublicationBoundaryPreservesOriginalOrPublishesWholeCandidate()
      throws Exception {
    for (String boundary : List.of("publishing", "old-moved", "candidate-published", "committed")) {
      // independent authority per injected fault
      File app = temporary.newFolder(), domain = new File(app, "files");
      fs.directory(domain);
      Files.createDirectories(new File(domain, "linux/ubuntu").toPath());
      var old = fs.stat(new File(domain, "linux"));
      var authority = ColdInstallPackages.bindPrivateFiles(fs, app, domain);
      var tx =
          ColdInstallTransaction.create(
              fs,
              authority,
              "1".repeat(64),
              at -> {
                if (at.equals(boundary)) throw new IOException(at);
              });
      data(tx);
      tx.prepared(() -> {});
      assertThrows(IOException.class, () -> tx.publish(() -> {}, () -> {}, () -> {}));
      if (boundary.equals("committed")) {
        assertTrue(new File(domain, "linux/ubuntu/bin/bash").isFile());
        assertEquals(old.key, fs.stat(new File(tx.directory(), "original-linux")).key);
      } else assertEquals(old.key, fs.stat(new File(domain, "linux")).key);
      assertTrue(ColdInstallTransaction.pending(fs, domain).isEmpty());
    }
  }

  @Test
  public void candidateReplacementOrModificationCannotPublish() throws Exception {
    var tx = create(null);
    data(tx);
    tx.prepared(() -> {});
    Files.writeString(new File(tx.candidate().root(), "bin/bash").toPath(), "modified");
    assertThrows(
        IOException.class,
        () ->
            tx.publish(
                () -> {},
                () -> {
                  throw new AssertionError();
                },
                () -> {
                  throw new AssertionError();
                }));
    assertFalse(new File(files, "linux").exists());
  }

  @Test
  public void successPublishesOneCompleteLinuxAndModeBeforeReady() throws Exception {
    var tx = create(null);
    data(tx);
    tx.prepared(() -> {});
    List<String> events = new ArrayList<>();
    tx.publish(() -> {}, () -> events.add("mode"), () -> events.add("ready"));
    assertEquals(List.of("mode", "ready"), events);
    assertTrue(new File(files, "linux/ubuntu/bin/bash").isFile());
    assertFalse(tx.candidate().linux().exists());
    assertTrue(ColdInstallTransaction.pending(fs, files).isEmpty());
  }

  @Test
  public void crashAfterCandidateMoveRecoversOnlyTheSameOriginal() throws Exception {
    Files.createDirectories(new File(files, "linux/ubuntu").toPath());
    String old = fs.stat(new File(files, "linux")).key;
    var tx =
        create(
            at -> {
              if (at.equals("candidate-published"))
                throw new AssertionError("simulated process death");
            });
    data(tx);
    tx.prepared(() -> {});
    assertThrows(AssertionError.class, () -> tx.publish(() -> {}, () -> {}, () -> {}));
    tx.release();
    assertEquals(List.of(tx.directory().getName()), ColdInstallTransaction.pending(fs, files));
    assertThrows(
        IOException.class,
        () ->
            ColdInstallTransaction.recover(
                fs,
                files,
                () -> {
                  throw new IOException("unknown guest");
                }));
    ColdInstallTransaction.recover(fs, files, () -> {});
    assertEquals(old, fs.stat(new File(files, "linux")).key);
    assertTrue(new File(tx.directory(), "failed-linux/ubuntu/bin/bash").isFile());
  }

  @Test
  public void interruptedTrackedProbeKeepsBarrierUntilItsDurableSessionWasReaped()
      throws Exception {
    var tx = create(null);
    data(tx);
    String id = UUID.randomUUID().toString();
    File records = new File(files, "bounded-guest-active");
    fs.directory(records);
    fs.directory(new File(records, id));
    tx.candidate().beforeLaunch(id);
    tx.release();
    assertEquals(1, ColdInstallTransaction.pending(fs, files).size());
    assertThrows(IOException.class, () -> ColdInstallTransaction.recover(fs, files, () -> {}));
    assertThrows(IOException.class, () -> create(null));
    fs.delete(new File(records, id));
    ColdInstallTransaction.recover(fs, files, () -> {});
    assertTrue(ColdInstallTransaction.pending(fs, files).isEmpty());
    assertTrue(new File(tx.candidate().root(), "bin/bash").isFile());
    assertFalse(new File(files, "linux").exists());
  }

  @Test
  public void readyMarkerFailureRestoresOnlyBoundColdPreferencesAndTree() throws Exception {
    Files.createDirectories(new File(files, "linux/ubuntu").toPath());
    var tx = create(null);
    data(tx);
    Map<String, Object> before =
        Map.of(
            "container_runtime",
            "proroot",
            "proot_disable_seccomp",
            false,
            "cold_runtime_root",
            "old-root");
    Map<String, Object> prefs = new HashMap<>(before);
    tx.selectionBefore(before);
    tx.prepared(() -> {});
    String expected =
        BackupJson.string(
            BackupJson.read(
                fs.small(new File(tx.directory(), "selection-before.json"), 16384), 16384),
            "expectedRoot");
    assertThrows(
        IOException.class,
        () ->
            tx.publish(
                () -> {},
                () -> {
                  prefs.put("container_runtime", "proot");
                  prefs.put("cold_runtime_root", expected);
                },
                () -> {
                  Files.writeString(
                      new File(files, "linux/.offline-extracted").toPath(), "partial ready");
                  throw new IOException("descriptor sync failed");
                },
                () ->
                    tx.restoreSelection(
                        (saved, root) -> {
                          assertEquals(expected, root);
                          assertEquals(expected, prefs.get("cold_runtime_root"));
                          prefs.clear();
                          prefs.putAll(saved);
                        })));
    assertEquals(before, prefs);
    assertFalse(new File(files, "linux/.offline-extracted").exists());
    assertTrue(new File(tx.directory(), "failed-linux/.offline-extracted").isFile());
  }

  @Test
  public void preferencesCommitFalseAndLaterRollbackFailureKeepRecoverableJournal()
      throws Exception {
    Files.createDirectories(new File(files, "linux/ubuntu").toPath());
    var tx = create(null);
    data(tx);
    Map<String, Object> before = Map.of("container_runtime", "proroot");
    Map<String, Object> prefs = new HashMap<>(before);
    tx.selectionBefore(before);
    tx.prepared(() -> {});
    String expected =
        BackupJson.string(
            BackupJson.read(
                fs.small(new File(tx.directory(), "selection-before.json"), 16384), 16384),
            "expectedRoot");
    assertThrows(
        IOException.class,
        () ->
            tx.publish(
                () -> {},
                () -> {
                  prefs.put("container_runtime", "proot");
                  prefs.put("cold_runtime_root", expected);
                  throw new IOException("commit false after memory apply");
                },
                () -> {
                  throw new AssertionError("must not publish ready");
                },
                () -> {
                  throw new IOException("rollback persistence unavailable");
                }));
    tx.release();
    assertEquals(List.of(tx.directory().getName()), ColdInstallTransaction.pending(fs, files));
    assertFalse(new File(files, "linux/.offline-extracted").exists());
    ColdInstallTransaction.recover(
        fs,
        files,
        () -> {},
        (saved, root) -> {
          assertEquals(expected, root);
          prefs.clear();
          prefs.putAll(saved);
        });
    assertEquals(before, prefs);
    assertTrue(ColdInstallTransaction.pending(fs, files).isEmpty());
  }
}
