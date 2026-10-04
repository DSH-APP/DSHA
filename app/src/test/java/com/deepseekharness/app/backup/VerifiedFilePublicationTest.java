package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.FileIntegrity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class VerifiedFilePublicationTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private File source(File folder) throws IOException {
    File source = new File(folder, "source");
    Files.writeString(source.toPath(), "original data");
    return source;
  }

  private FileIntegrity.Result expected(File source) throws IOException {
    try (InputStream in = new FileInputStream(source)) {
      return FileIntegrity.copy(in, null, source.length());
    }
  }

  @Test
  public void verifiedBytesPublishOnceAndSynchronizeTheParentDirectory() throws Exception {
    File folder = temporary.newFolder(),
        source = source(folder),
        target = new File(folder, "export");
    int[] syncs = {0};
    var fs =
        new JvmBackupFileSystem() {
          @Override
          public void syncDirectory(File directory) throws IOException {
            syncs[0]++;
            super.syncDirectory(directory);
          }
        };
    VerifiedFilePublication.publish(fs, source, target, expected(source));
    assertTrue(syncs[0] > 0);
    assertEquals("original data", Files.readString(target.toPath()));
    assertEquals("original data", Files.readString(source.toPath()));
    assertThrows(
        IOException.class,
        () -> VerifiedFilePublication.publish(fs, source, target, expected(source)));
    assertEquals(2, folder.list().length);
  }

  @Test
  public void failedRenameCleansOwnedStageAndPreservesEveryExistingFile() throws Exception {
    File folder = temporary.newFolder(),
        source = source(folder),
        target = new File(folder, "export");
    var fs =
        new JvmBackupFileSystem() {
          @Override
          public void move(File from, File to) throws IOException {
            Files.writeString(to.toPath(), "concurrent original");
            super.move(from, to);
          }
        };
    assertThrows(
        IOException.class,
        () -> VerifiedFilePublication.publish(fs, source, target, expected(source)));
    assertEquals("concurrent original", Files.readString(target.toPath()));
    assertEquals("original data", Files.readString(source.toPath()));
    assertEquals(2, folder.list().length);
  }

  @Test
  public void stageReadbackDetectsCorruptionBeforePublication() throws Exception {
    File folder = temporary.newFolder(),
        source = source(folder),
        target = new File(folder, "export");
    var fs =
        new JvmBackupFileSystem() {
          @Override
          public InputStream read(File file, Node node) throws IOException {
            if (file.getName().endsWith(".part")) Files.writeString(file.toPath(), "modified data");
            return super.read(file, node);
          }
        };
    assertThrows(
        IOException.class,
        () -> VerifiedFilePublication.publish(fs, source, target, expected(source)));
    assertFalse(target.exists());
    assertEquals("original data", Files.readString(source.toPath()));
    assertEquals(1, folder.list().length);
  }

  @Test
  public void syncFailureAfterPublicationKeepsTheCompleteOutputForRecovery() throws Exception {
    File folder = temporary.newFolder(),
        source = source(folder),
        target = new File(folder, "export");
    var fs =
        new JvmBackupFileSystem() {
          @Override
          public void syncDirectory(File directory) throws IOException {
            throw new IOException("DIRECTORY_SYNC_UNAVAILABLE");
          }
        };
    assertThrows(
        IOException.class,
        () -> VerifiedFilePublication.publish(fs, source, target, expected(source)));
    assertEquals("original data", Files.readString(target.toPath()));
    assertEquals("original data", Files.readString(source.toPath()));
    assertEquals(2, folder.list().length);
  }
}
