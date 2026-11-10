package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic isolated files: a second failure must not erase the original backup failure. */
public final class HostSnapshotFailureRetentionTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void captureWriteFailureSurvivesCleanupFailure() throws Exception {
    IOException writing = new IOException("synthetic write failure");
    IOException cleanup = new IOException("synthetic cleanup failure");
    BackupFileSystem fs =
        new JvmBackupFileSystem() {
          @Override
          public OutputStream create(File file) throws IOException {
            OutputStream output = super.create(file);
            if (!file.getName().equals("capture")) return output;
            return new FilterOutputStream(output) {
              @Override
              public void write(byte[] bytes, int offset, int count) throws IOException {
                throw writing;
              }
            };
          }

          @Override
          public void delete(File file) throws IOException {
            if (file.getName().equals("capture")) throw cleanup;
            super.delete(file);
          }
        };
    assertFailure(fs, writing, cleanup, false, false);
  }

  @Test
  public void captureWriteFailureSurvivesSourceCloseFailure() throws Exception {
    IOException writing = new IOException("synthetic write failure");
    BackupFileSystem fs =
        new JvmBackupFileSystem() {
          @Override
          public OutputStream create(File file) throws IOException {
            OutputStream output = super.create(file);
            if (!file.getName().equals("capture")) return output;
            return new FilterOutputStream(output) {
              @Override
              public void write(byte[] bytes, int offset, int count) throws IOException {
                throw writing;
              }
            };
          }
        };
    assertFailure(fs, writing, null, false, true);
  }

  @Test
  public void sourceReadFailureSurvivesSourceCloseFailure() throws Exception {
    assertFailure(new JvmBackupFileSystem(), null, null, true, true);
  }

  @Test
  public void sourceReadFailureSurvivesCaptureCloseFailure() throws Exception {
    IOException closing = new IOException("synthetic output close failure");
    BackupFileSystem fs =
        new JvmBackupFileSystem() {
          @Override
          public OutputStream create(File file) throws IOException {
            OutputStream output = super.create(file);
            if (!file.getName().equals("capture")) return output;
            return new FilterOutputStream(output) {
              @Override
              public void close() throws IOException {
                super.close();
                throw closing;
              }
            };
          }
        };
    assertFailure(fs, null, closing, true, false);
  }

  @Test
  public void archiveWriteFailureSurvivesCaptureCleanupFailure() throws Exception {
    IOException writing = new IOException("synthetic archive write failure");
    IOException cleanup = new IOException("synthetic cleanup failure");
    BackupFileSystem fs =
        new JvmBackupFileSystem() {
          @Override
          public OutputStream create(File file) throws IOException {
            OutputStream output = super.create(file);
            if (!file.getName().equals("archive")) return output;
            return new FilterOutputStream(output) {
              @Override
              public void write(byte[] bytes, int offset, int count) throws IOException {
                if (count == 15 && bytes[offset] == 's') throw writing;
                out.write(bytes, offset, count);
              }
            };
          }

          @Override
          public void delete(File file) throws IOException {
            if (file.getName().equals("capture")) throw cleanup;
            super.delete(file);
          }
        };
    assertFailure(fs, writing, cleanup, false, false);
  }

  private void assertFailure(
      BackupFileSystem fs,
      IOException expected,
      IOException expectedSuppressed,
      boolean failRead,
      boolean failClose)
      throws Exception {
    File source = temporary.newFolder(), task = temporary.newFolder();
    File original = new File(source, "synthetic.txt");
    Files.writeString(original.toPath(), "synthetic bytes");
    IOException reading = new IOException("synthetic read failure");
    IOException closing = new IOException("synthetic source close failure");
    int[] closed = {0};
    BackupSource root =
        new FileBackupSource(fs, "dsh", "application", source, false, null) {
          @Override
          public InputStream open(Item item) throws IOException {
            return new FilterInputStream(super.open(item)) {
              @Override
              public int read(byte[] bytes, int offset, int count) throws IOException {
                if (failRead) throw reading;
                return super.read(bytes, offset, count);
              }

              @Override
              public void close() throws IOException {
                closed[0]++;
                super.close();
                if (failClose) throw closing;
              }
            };
          }
        };
    IOException actual =
        assertThrows(
            IOException.class,
            () ->
                HostSnapshot.create(
                    fs,
                    List.of(root),
                    task,
                    new File(task, "archive"),
                    BackupArchiveTest.summary(),
                    false,
                    new BackupControl(null)));
    assertSame(expected == null ? reading : expected, actual);
    if (expectedSuppressed != null)
      assertTrue(List.of(actual.getSuppressed()).contains(expectedSuppressed));
    if (failClose) assertTrue(List.of(actual.getSuppressed()).contains(closing));
    assertEquals(1, closed[0]);
    assertEquals("synthetic bytes", Files.readString(original.toPath()));
  }
}
