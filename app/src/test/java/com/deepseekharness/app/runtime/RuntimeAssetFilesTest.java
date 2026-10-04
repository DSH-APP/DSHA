package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

public class RuntimeAssetFilesTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void atomicPublicationPreservesContentAndIdenticalInputDoesNotRewrite() throws Exception {
    File root = temporary.newFolder();
    var fs = new JvmBackupFileSystem();
    byte[] bytes = "first".getBytes();
    RuntimeAssetFiles.write(fs, root, "linux/marker", bytes, false);
    File target = new File(root, "linux/marker");
    assertArrayEquals(bytes, Files.readAllBytes(target.toPath()));
    var original = fs.stat(target);
    RuntimeAssetFiles.write(fs, root, "linux/marker", bytes, false);
    assertTrue(original.same(fs.stat(target)));
    RuntimeAssetFiles.write(fs, root, "linux/marker", "other".getBytes(), false);
    assertEquals("other", Files.readString(target.toPath()));
  }

  @Test
  public void directoryTargetIsNeverReplaced() throws Exception {
    File root = temporary.newFolder();
    Files.createDirectories(new File(root, "marker").toPath());
    assertThrows(
        java.io.IOException.class,
        () ->
            RuntimeAssetFiles.write(
                new JvmBackupFileSystem(), root, "marker", new byte[] {1}, false));
    assertTrue(new File(root, "marker").isDirectory());
  }

  @Test
  public void lexicalAuthorityCannotGrantParentTraversal() throws Exception {
    File declared = new File("/data/data/com.dsh.clienu/files"),
        physical = new File("/data/user/0/com.dsh.clienu/files");
    assertEquals(
        "linux/ubuntu/root/tool",
        RuntimeAssetFiles.relative(
            new File(declared, "linux/ubuntu/root/tool"), declared, physical));
    assertThrows(
        java.io.IOException.class,
        () -> RuntimeAssetFiles.relative(new File(declared, "../outside"), declared, physical));
    assertThrows(
        java.io.IOException.class,
        () ->
            RuntimeAssetFiles.relative(
                new File("/data/data/com.dsh.client/files/x"), declared, physical));
  }
}
