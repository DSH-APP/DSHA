package com.deepseekharness.app.core;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticHistoryTest {
  @Test
  public void tenThousandEventsRetainLatestCompleteLinesWithinBudget() {
    String value = "";
    for (int i = 0; i < 10000; i++) value = DiagnosticHistory.append(value, i + " 诊断事件\n");
    assertTrue(value.getBytes(StandardCharsets.UTF_8).length <= DiagnosticHistory.MAX_BYTES);
    assertTrue(value.endsWith("9999 诊断事件\n"));
    assertFalse(value.startsWith("0 诊断事件\n") || value.contains("\n0 诊断事件\n"));
  }

  @Test
  public void utf8BudgetKeepsCompleteLinesEvenWhenAlmostEveryCharacterIsMultibyte() {
    String previous = ("中文🙂".repeat(6000) + "\n");
    String value = DiagnosticHistory.append(previous, "latest 中文🙂\n");
    assertEquals("latest 中文🙂\n", value);
    assertTrue(value.getBytes(StandardCharsets.UTF_8).length <= DiagnosticHistory.MAX_BYTES);
    assertFalse(value.contains("\uFFFD"));
    String oversized = DiagnosticHistory.append("old\n", "🙂".repeat(10000));
    assertEquals("old\n[DIAGNOSTIC_EVENT_OVERSIZED]\n", oversized);
  }

  @Test
  public void symbolicLinkTargetCannotReplaceAnExternalFile() throws Exception {
    File directory = Files.createTempDirectory("diagnostic-link").toFile();
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    try {
      File target = new File(directory, "original.txt");
      Files.writeString(target.toPath(), "original\n");
      try {
        Files.createSymbolicLink(
            new File(directory, "diagnostic-events.txt").toPath(), target.toPath());
      } catch (java.nio.file.FileSystemException unavailable) {
        if (!System.getProperty("os.name").startsWith("Windows")) throw unavailable;
        org.junit.Assume.assumeNoException(
            "Windows host lacks symlink creation capability; LINK refusal has a portable test",
            unavailable);
      }
      assertThrows(
          IOException.class, () -> DiagnosticHistory.write(fs, directory, "replacement\n"));
      assertEquals("original\n", Files.readString(target.toPath()));
    } finally {
      fs.removeOwned(directory.getParentFile(), directory.getName());
    }
  }

  @Test
  public void linkMetadataRefusesTheWriteBeforeAnyTargetFileIsOpened() throws Exception {
    File directory = Files.createTempDirectory("diagnostic-link-metadata").toFile();
    JvmBackupFileSystem cleanup = new JvmBackupFileSystem();
    File target = new File(directory, "original.txt");
    try {
      Files.writeString(target.toPath(), "original\n");
      JvmBackupFileSystem fs =
          new JvmBackupFileSystem() {
            public com.deepseekharness.app.backup.BackupFileSystem.Node stat(File file)
                throws IOException {
              return file.getName().equals("diagnostic-events.txt")
                  ? new com.deepseekharness.app.backup.BackupFileSystem.Node(
                      "LINK", "link", 0, 0, 0, 0777)
                  : super.stat(file);
            }

            public java.io.InputStream read(
                File file, com.deepseekharness.app.backup.BackupFileSystem.Node expected) {
              throw new AssertionError("the linked target must never be opened");
            }
          };
      assertThrows(
          IOException.class, () -> DiagnosticHistory.write(fs, directory, "replacement\n"));
      assertEquals("original\n", Files.readString(target.toPath()));
    } finally {
      cleanup.removeOwned(directory.getParentFile(), directory.getName());
    }
  }

  @Test
  public void interruptedPublicationReadsPreviousAndNextWritePreservesHistory() throws Exception {
    File directory = Files.createTempDirectory("diagnostic-history").toFile();
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    try {
      Files.writeString(
          new File(directory, "diagnostic-events.txt.previous").toPath(), "complete old\n");
      assertEquals("complete old\n", DiagnosticHistory.read(fs, directory));
      DiagnosticHistory.write(fs, directory, "new\n");
      assertEquals("complete old\nnew\n", DiagnosticHistory.read(fs, directory));
    } finally {
      fs.removeOwned(directory.getParentFile(), directory.getName());
    }
  }

  @Test
  public void failedNewPublicationRetainsTheCompleteOldRecord() throws Exception {
    File directory = Files.createTempDirectory("diagnostic-failure").toFile();
    JvmBackupFileSystem cleanup = new JvmBackupFileSystem();
    try {
      Files.writeString(new File(directory, "diagnostic-events.txt").toPath(), "old\n");
      JvmBackupFileSystem fs =
          new JvmBackupFileSystem() {
            @Override
            public void move(File from, File to) throws IOException {
              if (from.getName().contains(".tmp-"))
                throw new IOException("INJECTED_PUBLICATION_FAILURE");
              super.move(from, to);
            }
          };
      try {
        DiagnosticHistory.write(fs, directory, "lost\n");
        fail();
      } catch (IOException expected) {
        assertEquals("INJECTED_PUBLICATION_FAILURE", expected.getMessage());
      }
      assertEquals("old\n", DiagnosticHistory.read(cleanup, directory));
    } finally {
      cleanup.removeOwned(directory.getParentFile(), directory.getName());
    }
  }
}
