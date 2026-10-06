package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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

  @Test
  public void treeDigestReadsBytesBeyondBufferEvenWhenMetadataIsUnchanged() throws Exception {
    File root = temporary.newFolder("digest"), file = new File(root, "data");
    byte[] bytes = new byte[65537];
    Files.write(file.toPath(), bytes);
    var time = Files.getLastModifiedTime(file.toPath());
    var filesystem = new JvmBackupFileSystem();
    var before = filesystem.stat(file);
    String original = BackupTree.digest(filesystem, root, new BackupControl(null));
    bytes[bytes.length - 1] = 1;
    Files.write(file.toPath(), bytes);
    Files.setLastModifiedTime(file.toPath(), time);
    assertTrue(before.same(filesystem.stat(file)));
    assertNotEquals(original, BackupTree.digest(filesystem, root, new BackupControl(null)));
  }

  @Test
  public void treeDigestKeepsExistingFieldsAndIndependentHashesWhenEngineIsReused()
      throws Exception {
    File root = temporary.newFolder("digest");
    Files.writeString(new File(root, "a").toPath(), "abc");
    Files.writeString(new File(root, "b").toPath(), "def");
    File empty = new File(root, "empty");
    Files.createDirectory(empty.toPath());
    Files.write(new File(empty, "zero").toPath(), new byte[0]);
    var filesystem =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            Node real = super.stat(file);
            // 固定跨平台模式，验收现有树摘要字节格式和逐文件 SHA 状态隔离。
            return new Node(real.type, real.key, real.size, real.modified, real.device, 0700);
          }
        };
    assertEquals(
        "2528e2912a6b1ba169754cecaad58c6313e35697dcf0c380c174f29be4774b76",
        BackupTree.digest(filesystem, root, new BackupControl(null)));
  }

  @Test
  public void treeDigestKeepsUtf8LinkFieldsAcrossMultipleBufferFlushes() throws Exception {
    File root = temporary.newFolder("digest");
    String target = "汉字/".repeat(300);
    var names = new java.util.ArrayList<String>();
    for (int i = 0; i < 64; i++) names.add("link" + (i < 10 ? "0" : "") + i);
    var filesystem =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            if (!file.equals(root))
              return new Node("LINK", file.getName(), target.length(), 1, 1, 0700);
            Node real = super.stat(file);
            return new Node(real.type, real.key, real.size, real.modified, real.device, 0700);
          }

          @Override
          public java.util.List<String> list(File file) {
            return names;
          }

          @Override
          public String readLink(File file) {
            return target;
          }

          @Override
          public InputStream read(File file, Node expected) {
            throw new AssertionError("链接目标不能进入文件读取路径");
          }
        };
    // 135832 字节的现有字段流，覆盖 UTF-8 长度、链接本体和两次以上的有界缓冲更新。
    assertEquals(
        "e977801dbdf83dd454bfbadfd2f839e133ec1326c375f9bc7d81067817d10946",
        BackupTree.digest(filesystem, root, new BackupControl(null)));
  }

  @Test
  public void treeDigestRejectsDirectoryReplacementAfterReadingItsChild() throws Exception {
    File root = temporary.newFolder("digest"), directory = new File(root, "directory");
    Files.createDirectory(directory.toPath());
    Files.writeString(new File(directory, "data").toPath(), "all bytes remain the same");
    JvmBackupFileSystem filesystem =
        new JvmBackupFileSystem() {
          boolean replaced;

          @Override
          public Node stat(File file) throws IOException {
            Node real = super.stat(file);
            return file.equals(directory)
                ? new Node(
                    real.type,
                    replaced ? "new-directory" : "old-directory",
                    real.size,
                    real.modified,
                    real.device,
                    real.mode)
                : real;
          }

          @Override
          public InputStream read(File file, Node expected) throws IOException {
            return new FilterInputStream(super.read(file, expected)) {
              @Override
              public void close() throws IOException {
                super.close();
                replaced = true;
              }
            };
          }
        };
    IOException failure =
        assertThrows(
            IOException.class, () -> BackupTree.digest(filesystem, root, new BackupControl(null)));
    assertEquals("SOURCE_CHANGED", failure.getMessage());
    assertEquals(
        "all bytes remain the same", Files.readString(new File(directory, "data").toPath()));
  }

  @Test
  public void freshNativeLeafTuplesAndPayloadKeepIndependentFileShaGolden() throws Exception {
    File root = temporary.newFolder("native-leaves");
    Files.writeString(new File(root, "a").toPath(), "abc");
    Files.writeString(new File(root, "b").toPath(), "def");
    Files.write(new File(root, "zero").toPath(), new byte[0]);
    BackupControl control = new BackupControl(null);
    LeafPort io = new LeafPort(root, control);
    var batch =
        new AndroidTreeFileSystem.LeafBatch(
            io, 7, 9, 10, new String[] {"a", "b", "zero"}, 3, control);
    byte[][] hashes = batch.digestSmall(0, 3, new byte[1048576], BackupArchive.sha(), control);
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        BackupArchive.hex(hashes[0]));
    assertEquals(
        "cb8379ac2098aa165029e3938a51da0bcecfc008fd6795f401178647f96c5b34",
        BackupArchive.hex(hashes[1]));
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        BackupArchive.hex(hashes[2]));
    assertEquals(1, io.statCalls);
    assertEquals(1, io.readCalls);
    assertEquals(0, io.open);
    assertEquals(3, io.closed);
    new AndroidTreeFileSystem.LeafBatch(io, 7, 9, 10, new String[] {"a", "b", "zero"}, 3, control);
    assertEquals("新一遍必须取得新tuple", 2, io.statCalls);
  }

  @Test
  public void changedNativeLeafNeverReturnsAUsableDigest() throws Exception {
    File root = temporary.newFolder("native-changed");
    Files.writeString(new File(root, "a").toPath(), "abc");
    BackupControl control = new BackupControl(null);
    LeafPort io = new LeafPort(root, control);
    var batch = new AndroidTreeFileSystem.LeafBatch(io, 7, 9, 10, new String[] {"a"}, 1, control);
    io.changeBeforeRead = true;
    IOException failure =
        assertThrows(
            IOException.class,
            () -> batch.digestSmall(0, 1, new byte[1048576], BackupArchive.sha(), control));
    assertEquals("SOURCE_CHANGED", failure.getMessage());
    assertEquals(0, io.open);
  }

  @Test
  public void cancelledNativeLeafPropagatesAfterOwnedIoFinallyCloses() throws Exception {
    File root = temporary.newFolder("native-cancel");
    Files.writeString(new File(root, "a").toPath(), "abc");
    BackupControl control = new BackupControl(null);
    LeafPort io = new LeafPort(root, control);
    var batch = new AndroidTreeFileSystem.LeafBatch(io, 7, 9, 10, new String[] {"a"}, 1, control);
    io.cancelOnRead = true;
    IOException failure =
        assertThrows(
            IOException.class,
            () -> batch.digestSmall(0, 1, new byte[1048576], BackupArchive.sha(), control));
    assertTrue(failure instanceof java.io.InterruptedIOException);
    assertEquals("CANCELLED", failure.getMessage());
    assertEquals(0, io.open);
    assertEquals(1, io.closed);
  }

  @Test
  public void largeLeafRemainsIneligibleAndOriginalDigestReadsEveryByte() throws Exception {
    File root = temporary.newFolder("native-large"), file = new File(root, "large");
    byte[] bytes = new byte[65537];
    bytes[bytes.length - 1] = 7;
    Files.write(file.toPath(), bytes);
    BackupControl control = new BackupControl(null);
    LeafPort io = new LeafPort(root, control);
    var batch =
        new AndroidTreeFileSystem.LeafBatch(io, 7, 9, 10, new String[] {"large"}, 1, control);
    assertFalse(batch.small(0));
    assertThrows(
        IOException.class,
        () -> batch.digestSmall(0, 1, new byte[1048576], BackupArchive.sha(), control));
    assertEquals(0, io.readCalls);
    String before = BackupTree.digest(new JvmBackupFileSystem(), file, control);
    bytes[bytes.length - 1] = 8;
    Files.write(file.toPath(), bytes);
    assertNotEquals(before, BackupTree.digest(new JvmBackupFileSystem(), file, control));
  }

  /** 真实JVM字节和owned流；身份tuple与native前后核验端口显式模拟，不冒充Android syscall证明。 */
  private static final class LeafPort implements AndroidTreeFileSystem.LeafReadPort {
    final File root;
    final BackupControl owner;
    int statCalls, readCalls, open, closed;
    boolean changeBeforeRead, cancelOnRead;

    LeafPort(File root, BackupControl owner) {
      this.root = root;
      this.owner = owner;
    }

    public void stat(
        int parent,
        long device,
        long inode,
        String[] names,
        int count,
        long[] output,
        com.deepseekharness.app.runtime.NativeStorage.ReadControl control)
        throws IOException {
      statCalls++;
      for (int entry = 0; entry < count; entry++) {
        control.check();
        File file = new File(root, names[entry]);
        int at = entry * 6;
        output[at] = 1;
        output[at + 1] = names[entry].hashCode();
        output[at + 2] = Files.size(file.toPath());
        output[at + 3] = 10;
        output[at + 4] = 0700;
        output[at + 5] = 1;
      }
    }

    public int read(
        int parent,
        long device,
        long inode,
        String[] names,
        int count,
        long[] expected,
        byte[] payload,
        int[] offsets,
        com.deepseekharness.app.runtime.NativeStorage.ReadControl control)
        throws IOException {
      readCalls++;
      if (changeBeforeRead) Files.writeString(new File(root, names[0]).toPath(), "changed longer");
      int used = 0;
      for (int entry = 0; entry < count; entry++) {
        control.check();
        File file = new File(root, names[entry]);
        if (Files.size(file.toPath()) != expected[entry * 6 + 2])
          throw new IOException("SOURCE_CHANGED");
        offsets[entry] = used;
        open++;
        try (InputStream input = Files.newInputStream(file.toPath())) {
          if (cancelOnRead) owner.cancel();
          control.check();
          byte[] bytes = new byte[4096];
          int countRead;
          while ((countRead = input.read(bytes)) != -1) {
            control.check();
            System.arraycopy(bytes, 0, payload, used, countRead);
            used += countRead;
          }
          if (used - offsets[entry] != expected[entry * 6 + 2]
              || Files.size(file.toPath()) != expected[entry * 6 + 2])
            throw new IOException("SOURCE_CHANGED");
        } finally {
          open--;
          closed++;
        }
      }
      offsets[count] = used;
      return used;
    }
  }
}
