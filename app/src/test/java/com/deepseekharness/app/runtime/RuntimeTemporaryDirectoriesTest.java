package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.nio.file.Files;
import java.io.IOException;
import org.junit.Test;

public class RuntimeTemporaryDirectoriesTest {
  @Test
  public void rootfsWithoutTmpSupportsRealSubprocessTemporaryFiles() throws Exception {
    var root = Files.createTempDirectory("guest-temp-root");
    Files.createDirectory(root.resolve("var"));
    RuntimeTemporaryDirectories.prepare(new JvmBackupFileSystem(), root.toFile());
    var child = Files.createTempDirectory(root.resolve("tmp"), "dsh-subprocess-");
    Files.writeString(child.resolve("probe"), "created");
    assertEquals("created", Files.readString(child.resolve("probe")));
    assertTrue(Files.isDirectory(root.resolve("var/tmp")));
  }

  @Test
  public void existingTemporaryContentsAndPermissionsArePreserved() throws Exception {
    var root = Files.createTempDirectory("guest-temp-existing");
    Files.createDirectories(root.resolve("var/tmp"));
    Files.createDirectory(root.resolve("tmp"));
    Files.writeString(root.resolve("tmp/keep"), "existing user file");
    var fs = new JvmBackupFileSystem();
    int mode = fs.stat(root.resolve("tmp").toFile()).mode;
    RuntimeTemporaryDirectories.prepare(fs, root.toFile());
    RuntimeTemporaryDirectories.prepare(fs, root.toFile());
    assertEquals("existing user file", Files.readString(root.resolve("tmp/keep")));
    assertEquals(mode, fs.stat(root.resolve("tmp").toFile()).mode);
  }

  @Test
  public void conflictingFileIsPreservedAndNeverReplaced() throws Exception {
    var root = Files.createTempDirectory("guest-temp-conflict");
    Files.writeString(root.resolve("tmp"), "original");
    assertThrows(
        IOException.class,
        () -> RuntimeTemporaryDirectories.prepare(new JvmBackupFileSystem(), root.toFile()));
    assertEquals("original", Files.readString(root.resolve("tmp")));
  }
}
