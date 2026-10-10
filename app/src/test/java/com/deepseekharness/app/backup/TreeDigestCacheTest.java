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

  /** 把见证丢掉的实现：模拟只用 6 参数构造 Node 的文件系统（ctime 恒为 0）。 */
  private static final class WitnesslessFileSystem implements BackupFileSystem {
    final CountingFileSystem io;

    WitnesslessFileSystem(CountingFileSystem io) {
      this.io = io;
    }

    public Node stat(File file) throws IOException {
      Node node = io.stat(file);
      return new Node(node.type, node.key, node.size, node.modified, node.device, node.mode);
    }

    public String readLink(File file) throws IOException {
      return io.readLink(file);
    }

    public List<String> list(File file) throws IOException {
      return io.list(file);
    }

    public InputStream read(File file, Node expected) throws IOException {
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

  /**
   * 封印要求「登记时刻的秒晚于树内最新 ctime 的秒」，所以断言复用的夹具必须先静置到下一个整秒，
   * 否则刚写完的树根本不会被登记，测试就退化成「反正没复用」。
   */
  private File agedTree() throws Exception {
    File root = tree();
    Assume.assumeTrue(
        "宿主不提供 ctime 见证（无 unix view），封印恒不成立，复用类断言无从谈起",
        fs.stat(new File(root, "a.txt")).ctime != 0);
    settle();
    return root;
  }

  /** 静置到下一个整秒：封印要求遍历所在秒严格晚于树内最新 ctime 的秒。 */
  private static void settle() throws InterruptedException {
    Thread.sleep(1100);
  }

  private static void bump(File file, String text) throws IOException {
    write(file, text);
    // mtime 是变化判据的一部分；显式前推让判定不依赖文件系统的时钟粒度。
    Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(file.lastModified() + 2000));
  }

  @Test
  public void matchesFullDigestAndReusesUnchangedTree() throws Exception {
    File root = agedTree();
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

  /**
   * mtime 是可由同 UID 写入者恢复的字段（{@code utimensat}），因此它不能单独充当「内容未变」的见证：
   * 等长改写 + 恢复 mtime 后 size/mtime/dev/inode 全同，只有内容变了。夹具必须先静置，让两次签名遍历
   * 都封印完成，否则这条用例只会因为「本来就没复用」而通过。
   */
  @Test
  public void recomputesWhenSameSizeRewriteRestoresMtime() throws Exception {
    File root = agedTree();
    File target = new File(root, "a.txt");
    long modified = target.lastModified();
    String before = cache.digest(fs, root, control);

    int afterRegistration = fs.reads.get();
    assertEquals("前置条件：封印成立，复用必须真的生效", before, cache.digest(fs, root, control));
    assertEquals("前置条件：复用时不得读取内容", afterRegistration, fs.reads.get());

    write(target, "ALPHA");
    assertTrue(target.setLastModified(modified));
    assertEquals("前置条件：mtime 已恢复为登记时的值", modified, target.lastModified());
    settle(); // 让查找侧也封印完成，复用条件真的成立，才谈得上「见证挡住了改写」

    int beforeRewrite = fs.reads.get();
    String after = cache.digest(fs, root, control);
    assertTrue("内容已变，必须重读而不能复用", fs.reads.get() > beforeRewrite);
    assertNotEquals("内容已变，绝不能复用旧摘要", before, after);
    assertEquals("必须与实际内容一致", BackupTree.digest(fs, root, control), after);
  }

  /** 封印规则的纯函数断言：同秒必须判为未封印，否则秒级 ctime 的同秒别名就漏了。 */
  @Test
  public void sealRequiresAStrictlyLaterSecond() {
    long second = 1_700_000_000L;
    assertFalse("同秒不得封印", TreeDigestCache.sealed(true, second, second * 1000L + 999));
    assertTrue("下一整秒起封印", TreeDigestCache.sealed(true, second, (second + 1) * 1000L));
    assertFalse("见证不完整不得封印", TreeDigestCache.sealed(false, second, (second + 1) * 1000L));
  }

  /**
   * 摘要前的那次遍历与树内最新 ctime 同秒时不得登记：这正是「与 before 同秒落笔」的写入者能骗过封印的
   * 窗口，登记必须在这一秒就放弃，而不是等登记时的那次遍历跨秒后才判定。
   */
  @Test
  public void doesNotRegisterWhenTheTreeWasWrittenInTheSameSecond() throws Exception {
    File root = tree();
    File target = new File(root, "a.txt");
    long second = System.currentTimeMillis() / 1000L;
    boolean sameSecond = false;
    for (int attempt = 0; attempt < 3 && !sameSecond; attempt++) {
      write(target, "alpha");
      sameSecond = second == fs.stat(target).ctime / 1_000_000_000L;
      second = System.currentTimeMillis() / 1000L;
    }
    Assume.assumeTrue("无法把写入固定在与遍历同一秒（整秒边界），跳过", sameSecond);

    TreeDigestCache fresh = new TreeDigestCache();
    assertEquals(BackupTree.digest(fs, root, control), fresh.digest(fs, root, control));
    int reads = fs.reads.get();
    fresh.digest(fs, root, control);
    assertTrue("同秒写入的树不得被登记，第二次摘要必须重读", fs.reads.get() > reads);
  }

  /** 没有见证时不得拿元数据当证据：必须每次重读内容，而不是复用旧摘要。 */
  @Test
  public void neverReusesWithoutWitness() throws IOException {
    WitnesslessFileSystem blind = new WitnesslessFileSystem(fs);
    TreeDigestCache blindCache = new TreeDigestCache();
    File root = tree();
    String expected = BackupTree.digest(blind, root, control);
    assertEquals(expected, blindCache.digest(blind, root, control));

    int reads = fs.reads.get();
    assertEquals("无见证时摘要值仍须正确", expected, blindCache.digest(blind, root, control));
    assertTrue("没有见证就必须放弃复用", fs.reads.get() > reads);
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
