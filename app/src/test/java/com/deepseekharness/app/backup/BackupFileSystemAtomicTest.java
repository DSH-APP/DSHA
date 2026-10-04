package com.deepseekharness.app.backup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.Assert.*;

public class BackupFileSystemAtomicTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private static final class FaultFs implements BackupFileSystem {
    final BackupFileSystem delegate;
    final int failMove;
    final boolean failPreviousDelete;
    int moves;

    FaultFs(BackupFileSystem delegate, int failMove, boolean failPreviousDelete) {
      this.delegate = delegate;
      this.failMove = failMove;
      this.failPreviousDelete = failPreviousDelete;
    }

    public Node stat(File f) throws IOException {
      return delegate.stat(f);
    }

    public String readLink(File f) throws IOException {
      return delegate.readLink(f);
    }

    public List<String> list(File f) throws IOException {
      return delegate.list(f);
    }

    public InputStream read(File f, Node n) throws IOException {
      return delegate.read(f, n);
    }

    public OutputStream create(File f) throws IOException {
      return delegate.create(f);
    }

    public void directory(File f) throws IOException {
      delegate.directory(f);
    }

    public void move(File a, File b) throws IOException {
      if (++moves == failMove) throw new IOException("PROCESS_INTERRUPTED");
      delegate.move(a, b);
    }

    public void delete(File f) throws IOException {
      if (failPreviousDelete && f.getName().equals("record.previous"))
        throw new IOException("PROCESS_INTERRUPTED_AFTER_PUBLISH");
      delegate.delete(f);
    }

    public void syncDirectory(File f) throws IOException {
      delegate.syncDirectory(f);
    }

    public void mode(File f, int m) throws IOException {
      delegate.mode(f, m);
    }

    public void symlink(String target, File link) throws IOException {
      delegate.symlink(target, link);
    }
  }

  @Test
  public void nextWriteRecoversAnInterruptedTargetToPreviousRename() throws Exception {
    File directory = temp.newFolder();
    fs.atomic(directory, "record", "old complete".getBytes());
    try {
      new FaultFs(fs, 2, false).atomic(directory, "record", "new complete".getBytes());
      fail();
    } catch (IOException expected) {
      assertEquals("PROCESS_INTERRUPTED", expected.getMessage());
    }
    assertEquals("MISSING", fs.stat(new File(directory, "record")).type);
    assertEquals("old complete", new String(fs.small(new File(directory, "record.previous"), 100)));
    fs.atomic(directory, "record", "new complete".getBytes());
    assertEquals("new complete", new String(fs.small(new File(directory, "record"), 100)));
    assertEquals("MISSING", fs.stat(new File(directory, "record.previous")).type);
    String retained =
        fs.list(directory).stream()
            .filter(name -> name.startsWith("record.previous-retained-"))
            .findFirst()
            .orElseThrow();
    assertEquals("old complete", new String(fs.small(new File(directory, retained), 100)));
    fs.atomic(directory, "record", "third complete".getBytes());
    assertEquals("old complete", new String(fs.small(new File(directory, retained), 100)));
  }

  @Test
  public void publishedRecordIsIdempotentAndRetainsAnUncleanedPrevious() throws Exception {
    File directory = temp.newFolder();
    fs.atomic(directory, "record", "old complete".getBytes());
    try {
      new FaultFs(fs, 0, true).atomic(directory, "record", "new complete".getBytes());
      fail();
    } catch (IOException expected) {
      assertEquals("PROCESS_INTERRUPTED_AFTER_PUBLISH", expected.getMessage());
    }
    assertEquals("new complete", new String(fs.small(new File(directory, "record"), 100)));
    fs.atomic(directory, "record", "new complete".getBytes());
    assertEquals("new complete", new String(fs.small(new File(directory, "record"), 100)));
    List<String> names = fs.list(directory);
    assertTrue(names.stream().anyMatch(name -> name.startsWith("record.previous-retained-")));
    assertEquals(
        "old complete",
        new String(
            fs.small(
                new File(
                    directory,
                    names.stream()
                        .filter(name -> name.startsWith("record.previous-retained-"))
                        .findFirst()
                        .orElseThrow()),
                100)));
  }

  @Test
  public void missingTargetWithNonFilePreviousFailsClosed() throws Exception {
    File directory = temp.newFolder(), previous = new File(directory, "record.previous");
    fs.directory(previous);
    fs.atomic(previous, "unknown", "keep original".getBytes());
    try {
      fs.atomic(directory, "record", "new value".getBytes());
      fail();
    } catch (IOException expected) {
      assertEquals("PREVIOUS_RECORD_UNREADABLE", expected.getMessage());
    }
    assertEquals("MISSING", fs.stat(new File(directory, "record")).type);
    assertEquals("keep original", new String(fs.small(fs.child(previous, "unknown"), 100)));
  }

  @Test
  public void failedExclusiveCreateNeverDeletesTheUnownedCollidingTemporaryFile() throws Exception {
    File directory = temp.newFolder();
    File[] unowned = {null};
    var collision =
        new JvmBackupFileSystem() {
          @Override
          public OutputStream create(File file) throws IOException {
            java.nio.file.Files.writeString(file.toPath(), "unknown original");
            unowned[0] = file;
            return super.create(file);
          }
        };
    assertThrows(IOException.class, () -> collision.atomic(directory, "record", "new".getBytes()));
    assertNotNull(unowned[0]);
    assertEquals("unknown original", java.nio.file.Files.readString(unowned[0].toPath()));
    assertFalse(new File(directory, "record").exists());
  }
}
