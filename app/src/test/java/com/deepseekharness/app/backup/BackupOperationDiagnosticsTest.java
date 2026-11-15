package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class BackupOperationDiagnosticsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String SECRET = "SYNTHETIC_PRIVATE_MESSAGE_/path?token=not-real";
  private static final String ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

  private static class ReadOnlyFs extends JvmBackupFileSystem {
    int reads;

    @Override
    public InputStream read(File file, Node expected) throws IOException {
      assertTrue(Set.of("operation.json", "operation.json.previous").contains(file.getName()));
      reads++;
      return super.read(file, expected);
    }

    @Override
    public OutputStream create(File file) {
      throw new AssertionError("must remain read-only");
    }

    @Override
    public void directory(File file) {
      throw new AssertionError("must remain read-only");
    }

    @Override
    public void move(File from, File to) {
      throw new AssertionError("must remain read-only");
    }

    @Override
    public void delete(File file) {
      throw new AssertionError("must remain read-only");
    }

    @Override
    public void syncDirectory(File file) {
      throw new AssertionError("must remain read-only");
    }
  }

  private File job(File files, boolean completed, String id, String stage, String error, long time)
      throws IOException {
    File job = new File(files, "host-backup-operations/" + (completed ? "completed/" : "") + id);
    Files.createDirectories(job.toPath());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("version", 1L);
    data.put("id", id);
    data.put("stage", stage);
    data.put("result", stage.equals("FINISHED") ? "COMPLETE" : "");
    data.put("error", error);
    data.put("artifact", "portable.dshbak");
    data.put("entries", 311L);
    data.put("bytes", 12345L);
    data.put("busy", false);
    data.put("updatedAt", time);
    Files.write(new File(job, "operation.json").toPath(), BackupJson.write(data, 16384));
    return job;
  }

  @Test
  public void activeAndCompletedFactsReadOnlyAndFailureAppearsBeforeNewerSuccess()
      throws Exception {
    File files = temporary.newFolder();
    File failed = job(files, true, ID, "FAILED", "IO_FAILURE", 1000);
    job(files, false, "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee", "FINISHED", "", 2000);
    Files.writeString(new File(failed, "verified.json").toPath(), SECRET);
    Files.writeString(new File(failed, "exported.json").toPath(), SECRET);
    Files.writeString(new File(failed, "failure.json").toPath(), SECRET);
    Files.writeString(new File(failed, "portable.dshbak").toPath(), SECRET);
    Files.writeString(new File(failed, "source-checks").toPath(), SECRET);
    ReadOnlyFs fs = new ReadOnlyFs();
    String report = BackupOperationDiagnostics.describe(fs, files);
    assertTrue(
        report.contains(
            "active=READ completed=READ scanned=2 shown=2 omitted=0 unavailable=0 complete=true"));
    assertTrue(report.indexOf("location=COMPLETED") < report.indexOf("location=ACTIVE"));
    assertTrue(report.contains("stage=FAILED result=NONE error=IO_FAILURE"));
    assertTrue(report.contains("updatedAtMs=1000"));
    assertTrue(report.contains("verified=FILE:" + SECRET.length()));
    assertTrue(report.contains("sourceChecks=FILE:" + SECRET.length()));
    assertFalse(report.contains(SECRET));
    assertFalse(report.contains(ID));
    assertFalse(report.contains(files.toString()));
    assertEquals(2, fs.reads);
  }

  @Test
  public void unknownMetadataValuesAndExtraFieldsNeverReachTheReport() throws Exception {
    File files = temporary.newFolder();
    File job = job(files, false, ID, SECRET, SECRET, 1000);
    var value =
        BackupJson.read(Files.readAllBytes(new File(job, "operation.json").toPath()), 16384);
    value.put("result", List.of(SECRET));
    value.put("artifact", SECRET);
    value.put("bytes", SECRET);
    value.put("extra", SECRET);
    Files.write(new File(job, "operation.json").toPath(), BackupJson.write(value, 16384));
    String report = BackupOperationDiagnostics.describe(new ReadOnlyFs(), files);
    assertFalse(report.contains(SECRET));
    assertTrue(report.contains("stage=UNRECOGNIZED result=UNRECOGNIZED error=UNRECOGNIZED"));
    assertTrue(report.contains("artifact=UNRECOGNIZED"));
    assertTrue(report.contains("bytes=-1"));
  }

  @Test
  public void malformedPreviousDoesNotHideCurrentFactsOrReadOtherFiles() throws Exception {
    File files = temporary.newFolder();
    File job = job(files, true, ID, "FAILED", "IO_FAILURE", 1);
    Files.writeString(new File(job, "operation.json.previous").toPath(), SECRET);
    String report = BackupOperationDiagnostics.describe(new ReadOnlyFs(), files);
    assertTrue(report.contains("stage=FAILED"));
    assertTrue(report.contains("previousRecordState=UNREADABLE"));
    assertFalse(report.contains(SECRET));
  }

  @Test
  public void missingAndUnavailableRootsNeverClaimAnEmptyVerifiedInventory() throws Exception {
    File files = temporary.newFolder();
    String missing = BackupOperationDiagnostics.describe(new ReadOnlyFs(), files);
    assertTrue(missing.contains("status=ROOT_MISSING complete=false jobs=UNKNOWN"));
    IOException failure =
        new IOException(SECRET) {
          @Override
          public String getMessage() {
            throw new AssertionError("must not read messages");
          }
        };
    String unavailable =
        BackupOperationDiagnostics.describe(
            new ReadOnlyFs() {
              @Override
              public Node stat(File file) throws IOException {
                throw failure;
              }
            },
            files);
    assertTrue(unavailable.contains("status=IO_UNAVAILABLE complete=false jobs=UNKNOWN"));
    assertFalse(unavailable.contains(SECRET));
  }

  @Test
  public void linkedJobAndOversizedMetadataAreNotOpened() throws Exception {
    File files = temporary.newFolder();
    File linked = job(files, false, ID, "FAILED", "IO_FAILURE", 1);
    File huge = job(files, true, "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee", "FAILED", "IO_FAILURE", 2);
    File linkedRecord =
        new File(
            job(files, true, "cccccccc-bbbb-cccc-dddd-eeeeeeeeeeee", "FAILED", "IO_FAILURE", 3),
            "operation.json");
    Files.write(new File(huge, "operation.json").toPath(), new byte[16385]);
    ReadOnlyFs fs =
        new ReadOnlyFs() {
          @Override
          public Node stat(File file) throws IOException {
            Node node = super.stat(file);
            return file.equals(linked) || file.equals(linkedRecord)
                ? new Node("LINK", node.key, 0, 0, 0, 0)
                : node;
          }
        };
    String report = BackupOperationDiagnostics.describe(fs, files);
    assertEquals(0, fs.reads);
    assertTrue(report.contains("unavailable=3 complete=false"));
    assertTrue(report.contains("recordState=UNREADABLE"));
  }

  @Test
  public void largeDirectoryIsBoundedBeforeOpeningAnyJob() throws Exception {
    File files = temporary.newFolder();
    File root = new File(files, "host-backup-operations");
    Files.createDirectory(root.toPath());
    ReadOnlyFs fs =
        new ReadOnlyFs() {
          @Override
          public List<String> list(File file) throws IOException {
            if (file.equals(root)) return Collections.nCopies(501, ID);
            return super.list(file);
          }
        };
    String report = BackupOperationDiagnostics.describe(fs, files);
    assertTrue(report.contains("active=NAME_LIMIT"));
    assertTrue(report.contains("scanned=0 shown=0 omitted=0 unavailable=0 complete=false"));
    assertEquals(0, fs.reads);
  }

  @Test
  public void fortyRowsRetainOldFailureAndDeclareOmittedSuccesses() throws Exception {
    File files = temporary.newFolder();
    for (int i = 0; i < 42; i++)
      job(
          files,
          true,
          String.format(Locale.ROOT, "%08x-bbbb-cccc-dddd-eeeeeeeeeeee", i),
          i == 0 ? "FAILED" : "FINISHED",
          i == 0 ? "IO_FAILURE" : "",
          i);
    String report = BackupOperationDiagnostics.describe(new ReadOnlyFs(), files);
    assertTrue(report.contains("scanned=42 shown=40 omitted=2"));
    assertTrue(report.split("\n")[1].contains("stage=FAILED"));
    assertTrue(report.length() <= 32768);
    assertEquals(41, report.split("\n").length);
  }

  @Test
  public void totalJobLimitStopsBeforeReadingTheFourHundredFirstRecord() throws Exception {
    File files = temporary.newFolder();
    File root = new File(files, "host-backup-operations");
    Files.createDirectory(root.toPath());
    List<String> names = new ArrayList<>();
    for (int i = 0; i < 401; i++)
      names.add(String.format(Locale.ROOT, "%08x-bbbb-cccc-dddd-eeeeeeeeeeee", i));
    ReadOnlyFs fs =
        new ReadOnlyFs() {
          @Override
          public List<String> list(File file) throws IOException {
            return file.equals(root) ? names : super.list(file);
          }

          @Override
          public Node stat(File file) throws IOException {
            if (root.equals(file.getParentFile()) && names.contains(file.getName()))
              return new Node("DIRECTORY", "synthetic", 0, 0, 0, 0700);
            return super.stat(file);
          }
        };
    String report = BackupOperationDiagnostics.describe(fs, files);
    assertTrue(report.contains("active=JOB_LIMIT"));
    assertTrue(report.contains("scanned=400 shown=40 omitted=360 unavailable=400 complete=false"));
    assertEquals(0, fs.reads);
  }

  @Test
  public void directoryMutationIsExplicitlyIncomplete() throws Exception {
    File files = temporary.newFolder();
    File job = job(files, false, ID, "FAILED", "IO_FAILURE", 1);
    File root = job.getParentFile();
    ReadOnlyFs fs =
        new ReadOnlyFs() {
          int rootStats;

          @Override
          public Node stat(File file) throws IOException {
            Node node = super.stat(file);
            if (file.equals(root) && ++rootStats > 2)
              return new Node(
                  node.type, node.key, node.size, node.modified + 1, node.device, node.mode);
            return node;
          }
        };
    String report = BackupOperationDiagnostics.describe(fs, files);
    assertTrue(report.contains("active=CHANGED"));
    assertTrue(report.contains("complete=false"));
  }

  @Test
  public void trustedAndroidRootAliasResolvesBeforeStrictDescendantListing() throws Exception {
    File files = temporary.newFolder();
    job(files, true, ID, "FAILED", "IO_FAILURE", 1);
    File alias =
        new File(temporary.getRoot(), "synthetic-android-alias/files") {
          @Override
          public File getCanonicalFile() throws IOException {
            return files.getCanonicalFile();
          }
        };
    ReadOnlyFs fs =
        new ReadOnlyFs() {
          @Override
          public List<String> list(File directory) throws IOException {
            if (directory.toPath().startsWith(alias.toPath())) throw new IOException("PARENT_LINK");
            return super.list(directory);
          }
        };
    // The Android filesystem refuses an unresolved alias; the trusted root adapter must
    // resolve it before opening actual job files, without weakening descendant checks.
    assertThrows(IOException.class, () -> fs.list(new File(alias, "host-backup-operations")));
    String report = BackupOperationDiagnostics.describe(fs, alias);
    assertTrue(report.contains("active=READ completed=READ scanned=1 shown=1"));
    assertTrue(report.contains("canonicalSame=false"));
    assertTrue(report.contains("stage=FAILED result=NONE error=IO_FAILURE"));
    assertEquals(1, fs.reads);
  }

  @Test
  public void canonicalRootFailureKeepsUnknownInventoryAndNeverReadsMessage() throws Exception {
    IOException secret =
        new IOException(SECRET) {
          @Override
          public String getMessage() {
            throw new AssertionError("must not read messages");
          }
        };
    File files =
        new File(temporary.getRoot(), "synthetic-root") {
          @Override
          public File getCanonicalFile() throws IOException {
            throw secret;
          }
        };
    String report = BackupOperationDiagnostics.describe(new ReadOnlyFs(), files);
    assertTrue(report.contains("status=IO_UNAVAILABLE complete=false jobs=UNKNOWN"));
    assertTrue(
        report.contains("canonicalSame=UNKNOWN failureStage=FILES_CANONICAL failureType=IO"));
    assertFalse(report.contains(SECRET));
  }

  @Test
  public void failedListReportsFixedStageTypeAndKnownProjectLineOnly() throws Exception {
    File files = temporary.newFolder();
    job(files, true, ID, "FAILED", "IO_FAILURE", 1);
    IOException secret =
        new IOException(SECRET) {
          @Override
          public String getMessage() {
            throw new AssertionError("must not read messages");
          }
        };
    secret.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("com.deepseekharness.app.backup." + SECRET, SECRET, SECRET, 33),
          new StackTraceElement(
              "com.deepseekharness.app.backup.AndroidBackupFileSystem", "anchored", SECRET, 39)
        });
    ReadOnlyFs fs =
        new ReadOnlyFs() {
          @Override
          public List<String> list(File directory) throws IOException {
            throw secret;
          }
        };
    String report = BackupOperationDiagnostics.describe(fs, files);
    assertTrue(report.contains("active=UNAVAILABLE completed=UNAVAILABLE scanned=0"));
    assertTrue(
        report.contains(
            "activeFailureStage=DIRECTORY_LIST activeFailureType=IO activeFailureErrno=-1 activeFailureSite=AndroidBackupFileSystem:39"));
    assertTrue(report.contains("completedFailureStage=DIRECTORY_LIST completedFailureType=IO"));
    assertFalse(report.contains(SECRET));
    assertTrue(report.contains("complete=false"));
  }

  @Test
  public void securityFailureIsDistinguishedWithoutEchoingItsText() throws Exception {
    File files = temporary.newFolder();
    job(files, false, ID, "FAILED", "IO_FAILURE", 1);
    String report =
        BackupOperationDiagnostics.describe(
            new ReadOnlyFs() {
              @Override
              public List<String> list(File directory) {
                throw new SecurityException(SECRET);
              }
            },
            files);
    assertTrue(report.contains("activeFailureStage=DIRECTORY_LIST activeFailureType=SECURITY"));
    assertTrue(report.contains("activeFailureErrno=-1"));
    assertFalse(report.contains(SECRET));
  }
}
