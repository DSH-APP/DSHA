package com.deepseekharness.app.saf;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 容器路径固定身份读写：真实文件系统 + 生命周期正确的 PFD 影子。
 *
 * <p>t10 P0 的根因是把「dup 后立刻 close 的编号」带到调用点；这里的 fake PFD 会让关闭后的
 * 编号直接失效，因此再用 `/proc/self/fd/<closed>` 读写/readlink 都会失败。测试同时覆盖
 * anchored 目录的创建/发布/关闭、祖先换链、硬链接与 FIFO、NOT_FOUND/新建分支。
 */
@RunWith(SafLocalFilesTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {SafTestOs.FakeOs.class, SafTestOs.FakePfd.class})
public final class SafLocalFilesTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected Properties getBuildSystemApiProperties() {
      return new Properties();
    }
  }

  private Path base;
  private File allowed;
  private File file;

  @Before
  public void setUp() throws Exception {
    SafTestOs.reset();
    SafLocalFiles.setDirectoryListerForTests(fd -> new String[0]);
    base = Files.createTempDirectory("dsha-saf-local-").toRealPath();
    allowed = new File(base.toFile(), "allowed");
    assertTrue(allowed.mkdirs());
    file = new File(allowed, "file.txt");
    Files.write(file.toPath(), "payload".getBytes(StandardCharsets.UTF_8));
  }

  @After
  public void tearDown() throws Exception {
    SafLocalFiles.setDirectoryListerForTests(null);
    try (var paths = Files.walk(base)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
  }

  @Test
  public void openReadReturnsContentAndClosesDupHandle() throws Exception {
    try (java.io.InputStream input = SafLocalFiles.openRead(file)) {
      assertEquals("payload", new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertTrue("readlink 用的 dup 句柄必须已关闭", SafTestOs.OPEN_FDS.isEmpty());
    assertTrue("流关闭后必须释放底层 fd", SafTestOs.RAW_HANDLES.isEmpty());
    assertTrue(SafTestOs.closedPfd > 0);
  }

  @Test
  public void anchoredDirectoryCreatesAndPublishesInsideItsFd() throws Exception {
    try (SafLocalFiles.AnchoredDirectory directory = SafLocalFiles.openAnchoredDirectory(allowed)) {
      assertTrue(SafTestOs.OPEN_FDS.contains(directory.fd));
      try (java.io.OutputStream output = SafLocalFiles.createInDirectory(directory, ".tmp")) {
        output.write("published".getBytes(StandardCharsets.UTF_8));
      }
      SafLocalFiles.publishInDirectory(directory, ".tmp", "out.txt");
      assertEquals(
          "published",
          new String(
              Files.readAllBytes(new File(allowed, "out.txt").toPath()), StandardCharsets.UTF_8));
    }
    assertTrue("AnchoredDirectory.close 必须关闭 dup 的 PFD", SafTestOs.OPEN_FDS.isEmpty());
    assertTrue("创建流关闭后必须释放底层 fd", SafTestOs.RAW_HANDLES.isEmpty());
    assertTrue(SafTestOs.closedPfd > 0);
  }

  @Test
  public void closedAnchoredDirectoryFdCannotBeUsedAgain() throws Exception {
    SafLocalFiles.AnchoredDirectory directory = SafLocalFiles.openAnchoredDirectory(allowed);
    int fd = directory.fd;
    directory.close();
    assertFalse("close 之后编号必须失效", SafTestOs.OPEN_FDS.contains(fd));
    assertThrows(IOException.class, () -> SafLocalFiles.createInDirectory(directory, "later.txt"));
  }

  @Test
  public void presetHardlinkIsRejectedBeforeAnyTruncate() throws Exception {
    File hardlink = new File(allowed, ".preset-temp");
    Files.createLink(hardlink.toPath(), file.toPath());
    try (SafLocalFiles.AnchoredDirectory directory = SafLocalFiles.openAnchoredDirectory(allowed)) {
      IOException error =
          assertThrows(
              IOException.class, () -> SafLocalFiles.createInDirectory(directory, ".preset-temp"));
      assertEquals("HARDLINK", error.getMessage());
    }
    assertEquals(
        "校验失败前不能截断原文件",
        "payload",
        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
  }

  @Test
  public void fifoIsRejectedAsNotRegularWithoutBlocking() throws Exception {
    File fifo = new File(allowed, "pipe");
    Process process = new ProcessBuilder("mkfifo", fifo.getAbsolutePath()).start();
    org.junit.Assume.assumeTrue("mkfifo 不可用", process.waitFor() == 0);
    IOException error = assertThrows(IOException.class, () -> SafLocalFiles.openRead(fifo));
    assertEquals("NOT_REGULAR", error.getMessage());
  }

  @Test
  public void ancestorSymlinkSwapIsRejectedByRealPathCheck() throws Exception {
    Path credentials = Files.createDirectories(base.resolve(".docker"));
    Path secret = credentials.resolve("config.json");
    Files.write(secret, "SECRET".getBytes(StandardCharsets.UTF_8));
    File leaf = new File(allowed, "leaf");
    assertTrue(leaf.mkdir());
    Files.write(leaf.toPath().resolve("config.json"), "allowed".getBytes(StandardCharsets.UTF_8));
    Files.delete(leaf.toPath().resolve("config.json"));
    assertTrue(leaf.delete());
    Files.createSymbolicLink(leaf.toPath(), credentials);

    IOException error =
        assertThrows(
            IOException.class, () -> SafLocalFiles.openRead(new File(leaf, "config.json")));
    assertEquals("PATH_CHANGED", error.getMessage());
  }

  @Test
  public void walkOpenNonDirectoryClosesOpenedFd() throws Exception {
    IOException error =
        assertThrows(IOException.class, () -> SafLocalFiles.openAnchoredDirectory(file));
    assertEquals("PARENT_NOT_DIRECTORY", error.getMessage());
    assertTrue("非目录失败分支必须关闭已打开的 fd", SafTestOs.RAW_HANDLES.isEmpty());
  }

  @Test
  public void repeatedReadsLeaveNoFileDescriptorsBehind() throws Exception {
    for (int i = 0; i < 100; i++) {
      try (java.io.InputStream input = SafLocalFiles.openRead(file)) {
        assertEquals('p', input.read());
      }
    }
    assertTrue("100 次读取后不能累积 fd", SafTestOs.RAW_HANDLES.isEmpty());
    assertTrue(SafTestOs.RAW_FILES.isEmpty());
  }

  @Test
  public void link2SymlinkEntityPathIsRejectedBeforeAnyOpen() {
    File hidden = new File(allowed, ".l2s/credential");
    IOException error = assertThrows(IOException.class, () -> SafLocalFiles.openRead(hidden));
    assertEquals("LINK2SYMLINK", error.getMessage());
  }

  @Test
  public void missingFileIsNotFoundWithoutOpeningAnything() {
    IOException error =
        assertThrows(IOException.class, () -> SafLocalFiles.openRead(new File(allowed, "none")));
    assertEquals("NOT_FOUND", error.getMessage());
  }

  @Test
  public void newFileIsCreatedOnlyAfterRealPathVerification() throws Exception {
    File fresh = new File(allowed, "fresh.txt");
    try (java.io.OutputStream output = SafLocalFiles.openWrite(fresh)) {
      output.write("new".getBytes(StandardCharsets.UTF_8));
    }
    assertEquals("new", new String(Files.readAllBytes(fresh.toPath()), StandardCharsets.UTF_8));
  }
}
