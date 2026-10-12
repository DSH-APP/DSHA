package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 摘要缓存必须与全量摘要完全一致，且只在树真的变化时重新读取内容。 */
public class TreeDigestCacheTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  /** 统计内容读取次数；其余全部委托给真实的 JVM 文件系统。 */
  private static final class CountingFileSystem implements BackupFileSystem {
    final JvmBackupFileSystem io = new JvmBackupFileSystem();
    final AtomicInteger reads = new AtomicInteger();

    public Node stat(File file) throws IOException {
      return io.stat(file);
    }

    public String readLink(File file) throws IOException {
      return io.readLink(file);
    }

    public List<String> list(File file) throws IOException {
      return io.list(file);
    }

    public InputStream read(File file, Node expected) throws IOException {
      reads.incrementAndGet();
      return io.read(file, expected);
    }

    public OutputStream create(File file) throws IOException {
      return io.create(file);
    }

    public void directory(File file) throws IOException {
      io.directory(file);
    }

    public void move(File source, File target) throws IOException {
      io.move(source, target);
    }

    public void delete(File file) throws IOException {
      io.delete(file);
    }

    public void mode(File file, int mode) throws IOException {
      io.mode(file, mode);
    }

    public void symlink(String target, File link) throws IOException {
      io.symlink(target, link);
    }

    public void syncDirectory(File file) throws IOException {
      io.syncDirectory(file);
    }
  }

  private final CountingFileSystem fs = new CountingFileSystem();
  private final TreeDigestCache cache = new TreeDigestCache();
  private final BackupControl control = new BackupControl(null);

  private static void write(File file, String text) throws IOException {
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  /** 目录树：file 的内容 + 子目录 + 符号链接，覆盖摘要涉及的每种成员。 */
  private File tree() throws IOException {
    File root = temporary.newFolder("root");
    write(new File(root, "a.txt"), "alpha");
    write(new File(root, "b.bin"), "beta-payload");
    File nested = new File(root, "nested");
    assertTrue(nested.mkdir());
    write(new File(nested, "c.txt"), "gamma");
    fs.symlink("a.txt", new File(root, "link"));
    return root;
  }

  private static void bump(File file, String text) throws IOException {
    write(file, text);
    // mtime 是变化判据的一部分；显式前推让判定不依赖文件系统的时钟粒度。
    Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(file.lastModified() + 2000));
  }

  @Test
  public void matchesFullDigestAndReusesUnchangedTree() throws IOException {
    File root = tree();
    String expected = BackupTree.digest(fs, root, control);
    int fullReads = fs.reads.get();
    assertTrue("全量摘要应当读取文件内容", fullReads > 0);

    String first = cache.digest(fs, root, control);
    assertEquals(expected, first);
    int afterFirst = fs.reads.get();
    assertTrue("首次缓存仍须读取内容以建立摘要", afterFirst > fullReads);

    String second = cache.digest(fs, root, control);
    assertEquals("缓存命中必须返回同一个摘要", first, second);
    assertEquals("树未变化时不得再次读取文件内容", afterFirst, fs.reads.get());
  }

  @Test
  public void recomputesWhenFileContentChanges() throws IOException {
    File root = tree();
    String before = cache.digest(fs, root, control);
    bump(new File(root, "a.txt"), "ALPHA-CHANGED");
    String after = cache.digest(fs, root, control);
    assertNotEquals("内容变化必须改变摘要", before, after);
    assertEquals("必须与实际内容一致", BackupTree.digest(fs, root, control), after);
  }

  @Test
  public void recomputesWhenEntryAddedOrRemoved() throws IOException {
    File root = tree();
    String before = cache.digest(fs, root, control);
    write(new File(root, "added.txt"), "new");
    String added = cache.digest(fs, root, control);
    assertNotEquals(before, added);
    assertEquals(BackupTree.digest(fs, root, control), added);

    assertTrue(new File(root, "added.txt").delete());
    String removed = cache.digest(fs, root, control);
    assertEquals("删除后必须回到原摘要", before, removed);
  }

  @Test
  public void recomputesWhenModeChanges() throws IOException {
    File root = tree();
    fs.mode(new File(root, "a.txt"), 0600);
    String before = cache.digest(fs, root, control);
    fs.mode(new File(root, "a.txt"), 0644);
    String after = cache.digest(fs, root, control);
    assertNotEquals("权限变化必须改变摘要", before, after);
    assertEquals(BackupTree.digest(fs, root, control), after);
  }

  @Test
  public void recomputesWhenLinkTargetChanges() throws IOException {
    File root = tree();
    String before = cache.digest(fs, root, control);
    File link = new File(root, "link");
    assertTrue(link.delete());
    fs.symlink("b.bin", link);
    String after = cache.digest(fs, root, control);
    assertNotEquals("链接目标变化必须改变摘要", before, after);
    assertEquals(BackupTree.digest(fs, root, control), after);
  }

  @Test
  public void recomputesWhenFileIsRewrittenToTheSameSize() throws IOException {
    File root = tree();
    File target = new File(root, "a.txt");
    bump(target, "alpha");
    String before = cache.digest(fs, root, control);
    // 等长改写：判据靠 mtime/inode 而不是内容比对，与 Node.same 的强度一致。
    bump(target, "ALPHA");
    String after = cache.digest(fs, root, control);
    assertNotEquals(before, after);
    assertEquals(BackupTree.digest(fs, root, control), after);
  }

  @Test
  public void doesNotCacheNonDirectoryRoot() throws IOException {
    File file = temporary.newFile("standalone.bin");
    write(file, "standalone");
    String expected = BackupTree.digest(fs, file, control);
    assertEquals(expected, cache.digest(fs, file, control));
    int reads = fs.reads.get();
    assertEquals(expected, cache.digest(fs, file, control));
    assertTrue("非目录根不缓存，必须仍走全量摘要", fs.reads.get() > reads);
  }
}
