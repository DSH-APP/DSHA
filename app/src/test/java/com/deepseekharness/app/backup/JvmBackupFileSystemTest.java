package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real JVM file operations plus explicit device-fault injection; no Android O_PATH proof. */
public final class JvmBackupFileSystemTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void deviceIsPartOfIdentityEvenWhenFileKeyAndOtherFieldsMatch() {
    BackupFileSystem.Node before = new BackupFileSystem.Node("FILE", "same-key", 4, 10, 1, 0600);
    BackupFileSystem.Node other = new BackupFileSystem.Node("FILE", "same-key", 4, 10, 2, 0600);
    assertFalse(before.same(other));
  }

  @Test
  public void existingTargetNeverOverwritesEitherOriginal() throws Exception {
    File source = temporary.newFile("source"), target = temporary.newFile("target");
    Files.writeString(source.toPath(), "new");
    Files.writeString(target.toPath(), "original");
    assertThrows(
        FileAlreadyExistsException.class, () -> new JvmBackupFileSystem().move(source, target));
    assertEquals("new", Files.readString(source.toPath()));
    assertEquals("original", Files.readString(target.toPath()));
  }

  @Test
  public void injectedDifferentDeviceRefusesMoveWithoutTouchingSource() throws Exception {
    File source = temporary.newFile("source"), targetParent = temporary.newFolder("target-parent");
    Files.writeString(source.toPath(), "original");
    File target = new File(targetParent, "target");
    JvmBackupFileSystem injected =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            Node real = super.stat(file);
            return new Node(
                real.type,
                real.key,
                real.size,
                real.modified,
                file.equals(targetParent) ? 2 : 1,
                real.mode);
          }
        };
    IOException failure = assertThrows(IOException.class, () -> injected.move(source, target));
    assertEquals("UNSAFE_MOVE", failure.getMessage());
    assertEquals("original", Files.readString(source.toPath()));
    assertFalse(target.exists());
  }

  @Test
  public void realSymlinkParentCannotReceiveMove() throws Exception {
    Assume.assumeFalse(
        "Windows host has no ordinary symlink creation privilege",
        System.getProperty("os.name").startsWith("Windows"));
    File source = temporary.newFile("source"), actual = temporary.newFolder("actual");
    File link = new File(temporary.getRoot(), "link");
    Files.createSymbolicLink(link.toPath(), actual.toPath());
    IOException failure =
        assertThrows(
            IOException.class,
            () -> new JvmBackupFileSystem().move(source, new File(link, "target")));
    assertEquals("PARENT_LINK", failure.getMessage());
    assertTrue(source.exists());
    assertFalse(new File(actual, "target").exists());
  }

  @Test
  public void posixModeChangeInvalidatesPreviousIdentity() throws Exception {
    Assume.assumeFalse(
        "Windows host does not expose POSIX mode bits",
        System.getProperty("os.name").startsWith("Windows"));
    File file = temporary.newFile("mode");
    JvmBackupFileSystem filesystem = new JvmBackupFileSystem();
    filesystem.mode(file, 0600);
    BackupFileSystem.Node before = filesystem.stat(file);
    filesystem.mode(file, 0700);
    BackupFileSystem.Node after = filesystem.stat(file);
    assertEquals(0600, before.mode);
    assertEquals(0700, after.mode);
    assertFalse(before.same(after));
  }

  @Test
  public void injectedParentLinkIsRejectedOnEveryHost() throws Exception {
    File source = temporary.newFile("source"), targetParent = temporary.newFolder("parent");
    JvmBackupFileSystem injected =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            Node real = super.stat(file);
            return file.equals(targetParent)
                ? new Node("LINK", real.key, real.size, real.modified, real.device, real.mode)
                : real;
          }
        };
    IOException failure =
        assertThrows(
            IOException.class, () -> injected.move(source, new File(targetParent, "target")));
    assertEquals("PARENT_LINK", failure.getMessage());
    assertTrue(source.exists());
    assertFalse(new File(targetParent, "target").exists());
  }
}
