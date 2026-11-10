package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class BackupTemporaryFilesTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void firstCleanupFailureStillCleansOtherOwnedFilesAndPreservesPrimary() throws Exception {
    File first = temporary.newFile(), second = temporary.newFile();
    IOException original = new IOException("synthetic operation failure");
    IOException cleanup = new IOException("synthetic cleanup failure");
    BackupFileSystem fs =
        new JvmBackupFileSystem() {
          @Override
          public void delete(File file) throws IOException {
            if (file.equals(first)) throw cleanup;
            super.delete(file);
          }
        };
    IOException actual =
        assertThrows(
            IOException.class,
            () -> {
              try (BackupTemporaryFiles ignored = new BackupTemporaryFiles(fs, first, second)) {
                throw original;
              }
            });
    assertSame(original, actual);
    assertArrayEquals(new Throwable[] {cleanup}, actual.getSuppressed());
    assertTrue(first.exists());
    assertFalse(second.exists());
  }

  @Test
  public void cleanupOnlyFailureCannotTurnIntoSuccess() throws Exception {
    File first = temporary.newFile(), second = temporary.newFile();
    IOException firstFailure = new IOException("synthetic first cleanup failure");
    IOException secondFailure = new IOException("synthetic second cleanup failure");
    BackupFileSystem fs =
        new JvmBackupFileSystem() {
          @Override
          public void delete(File file) throws IOException {
            throw file.equals(first) ? firstFailure : secondFailure;
          }
        };
    IOException actual =
        assertThrows(IOException.class, () -> new BackupTemporaryFiles(fs, first, second).close());
    assertSame(firstFailure, actual);
    assertArrayEquals(new Throwable[] {secondFailure}, actual.getSuppressed());
    assertTrue(first.exists());
    assertTrue(second.exists());
  }

  @Test
  public void unexpectedDirectoryIsRetainedAndUnrelatedBytesAreUntouched() throws Exception {
    File directory = temporary.newFolder(), sibling = temporary.newFile();
    Files.writeString(sibling.toPath(), "synthetic retained bytes");
    IOException actual =
        assertThrows(
            IOException.class,
            () -> new BackupTemporaryFiles(new JvmBackupFileSystem(), directory).close());
    assertEquals("TEMPORARY_FILE_TYPE", actual.getMessage());
    assertTrue(directory.isDirectory());
    assertEquals("synthetic retained bytes", Files.readString(sibling.toPath()));
  }

  @Test
  public void successfulCleanupAndAlreadyMissingFilesRemainIdempotent() throws Exception {
    File first = temporary.newFile(), second = temporary.newFile();
    BackupTemporaryFiles cleanup =
        new BackupTemporaryFiles(new JvmBackupFileSystem(), first, second);
    cleanup.close();
    cleanup.close();
    assertFalse(first.exists());
    assertFalse(second.exists());
  }
}
