package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class BridgeFileSnapshotTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void publishedPrivateCopyKeepsSourceBytesAndIsNotAnOverwrite() throws Exception {
    BackupFileSystem fs = new JvmBackupFileSystem();
    File root = temp.newFolder(),
        source = new File(root, "source.txt"),
        copy = new File(root, "private.txt");
    Files.writeString(source.toPath(), "source-data");
    var result = BridgeFileSnapshot.copy(fs, source, copy, 20);
    assertEquals(11, result.size);
    assertEquals("source-data", Files.readString(copy.toPath()));
    assertEquals("source-data", Files.readString(source.toPath()));
    assertThrows(IOException.class, () -> BridgeFileSnapshot.copy(fs, source, copy, 20));
    assertEquals("source-data", Files.readString(source.toPath()));
    assertEquals("source-data", Files.readString(copy.toPath()));
  }

  @Test
  public void sizeLimitFailureDoesNotLeaveAValidCopy() throws Exception {
    BackupFileSystem fs = new JvmBackupFileSystem();
    File root = temp.newFolder(),
        source = new File(root, "source.txt"),
        copy = new File(root, "private.txt");
    Files.writeString(source.toPath(), "too-long");
    assertThrows(IOException.class, () -> BridgeFileSnapshot.copy(fs, source, copy, 3));
    assertFalse(copy.exists());
  }
}
