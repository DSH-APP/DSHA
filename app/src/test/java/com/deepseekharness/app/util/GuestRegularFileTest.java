package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GuestRegularFileTest {
  private static final String SQLITE = "usr/lib/aarch64-linux-gnu/libsqlite3.so.0";

  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private File rootfs() throws IOException {
    File root = temporary.newFolder("files", "linux", "ubuntu");
    Files.createDirectories(root.toPath().resolve("usr/lib/aarch64-linux-gnu"));
    return root;
  }

  private static Path write(File root, String relative, String content) throws IOException {
    Path path = root.toPath().resolve(relative);
    Files.createDirectories(path.getParent());
    Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    return path;
  }

  private static void link(File root, String relative, String target) throws IOException {
    Path path = root.toPath().resolve(relative);
    Files.createDirectories(path.getParent());
    Files.createSymbolicLink(path, Paths.get(target));
  }

  @Test
  public void plainFileIsReadyWithoutFollowingAnything() throws IOException {
    File root = rootfs();
    Path library = write(root, SQLITE, "elf");
    GuestRegularFile.Result result = GuestRegularFile.check(fs, root, SQLITE, null);
    assertTrue(result.regular());
    assertEquals(library.toFile().getAbsoluteFile(), result.file);
    assertEquals(0, result.links);
    assertEquals(3, result.node.size);
  }

  @Test
  public void hostParentLinkCannotSmuggleAFileFromOutsideTheRootfs() throws IOException {
    // 宿主 lstat 会跟随父目录链接；guest 解析必须拒绝指向 rootfs 外宿主目录的父链接。
    File root = rootfs();
    File outside = temporary.newFolder("outside", "aarch64-linux-gnu");
    Files.write(outside.toPath().resolve("libreadline.so.8"), new byte[] {1, 2});
    Files.delete(root.toPath().resolve("usr/lib/aarch64-linux-gnu"));
    link(root, "usr/lib/aarch64-linux-gnu", outside.getAbsolutePath());
    GuestRegularFile.Result result =
        GuestRegularFile.check(fs, root, "usr/lib/aarch64-linux-gnu/libreadline.so.8", null);
    assertFalse(result.regular());
    assertEquals("MISSING", result.failure);
  }

  @Test
  public void aptSonameLinkToRegularFileIsReady() throws IOException {
    // 现场：apt 安装 libsqlite3-0 后 libsqlite3.so.0 -> libsqlite3.so.0.8.6。
    File root = rootfs();
    Path real = write(root, "usr/lib/aarch64-linux-gnu/libsqlite3.so.0.8.6", "sqlite-elf");
    link(root, SQLITE, "libsqlite3.so.0.8.6");
    GuestRegularFile.Result result = GuestRegularFile.check(fs, root, SQLITE, null);
    assertTrue(result.failure, result.regular());
    assertEquals(real.toFile().getAbsoluteFile(), result.file);
    assertEquals(1, result.links);
    assertEquals(10, result.node.size);
  }

  @Test
  public void relativeChainsAndParentStepsStayInsideTheGuest() throws IOException {
    File root = rootfs();
    write(root, "opt/sqlite/lib/libsqlite3.so.0.8.6", "x");
    link(
        root,
        "usr/lib/aarch64-linux-gnu/libsqlite3.so.0.8",
        "../../../opt/sqlite/lib/libsqlite3.so.0.8.6");
    link(root, SQLITE, "libsqlite3.so.0.8");
    GuestRegularFile.Result result = GuestRegularFile.check(fs, root, SQLITE, null);
    assertTrue(result.failure, result.regular());
    assertEquals(2, result.links);
    // guest 根的 .. 仍是根：多余的 .. 不能走出 rootfs。
    link(root, "usr/bin/escape", "../../../../../../usr/lib/aarch64-linux-gnu/libsqlite3.so.0.8.6");
    write(root, "usr/lib/aarch64-linux-gnu/libsqlite3.so.0.8.6", "y");
    GuestRegularFile.Result clamped = GuestRegularFile.check(fs, root, "usr/bin/escape", null);
    assertTrue(clamped.failure, clamped.regular());
    assertTrue(clamped.file.getPath().startsWith(root.getAbsolutePath() + File.separator));
  }

  @Test
  public void guestAbsoluteLinkResolvesFromTheRootfsNotTheHost() throws IOException {
    File root = rootfs();
    write(root, "usr/lib/aarch64-linux-gnu/libsqlite3.so.0.8.6", "x");
    link(root, SQLITE, "/usr/lib/aarch64-linux-gnu/libsqlite3.so.0.8.6");
    GuestRegularFile.Result result = GuestRegularFile.check(fs, root, SQLITE, null);
    assertTrue(result.failure, result.regular());
    assertEquals(
        new File(root, "usr/lib/aarch64-linux-gnu/libsqlite3.so.0.8.6").getAbsoluteFile(),
        result.file);
  }

  @Test
  public void hostAbsolutePathsMapOnlyToThisRootfs() throws IOException {
    // proot --link2symlink 与外部 root 写入的宿主绝对路径：只映射回本次 rootfs。
    File root = rootfs();
    write(root, ".l2s/.l2s.libsqlite3.so.00001", "payload");
    link(root, SQLITE, root.getAbsolutePath() + "/.l2s/.l2s.libsqlite3.so.00001");
    assertTrue(GuestRegularFile.check(fs, root, SQLITE, null).regular());

    File other = temporary.newFolder("other");
    Files.write(other.toPath().resolve("lib"), new byte[] {1});
    link(root, "usr/lib/aarch64-linux-gnu/libreadline.so.8", other.getAbsolutePath() + "/lib");
    GuestRegularFile.Result outside =
        GuestRegularFile.check(fs, root, "usr/lib/aarch64-linux-gnu/libreadline.so.8", null);
    assertFalse("宿主上其它目录的文件不能借链接冒充运行文件", outside.regular());

    GuestRegularFile.Result mirrored =
        GuestRegularFile.check(
            fs,
            root,
            "usr/lib/aarch64-linux-gnu/libreadline.so.8",
            target ->
                target.startsWith(other.getAbsolutePath())
                    ? ".l2s/.l2s.libsqlite3.so.00001"
                    : null);
    assertTrue(mirrored.failure, mirrored.regular());
  }

  @Test
  public void externalBindingsAreNeverFollowed() throws IOException {
    File root = rootfs();
    for (String target :
        new String[] {
          "/system/lib64/libsqlite.so",
          "/proc/self/exe",
          "/apex/com.android.runtime/lib64/bionic/libc.so",
          "/sdcard/libsqlite3.so.0",
          "/data/local/tmp/libsqlite3.so.0"
        }) {
      Files.deleteIfExists(root.toPath().resolve(SQLITE));
      link(root, SQLITE, target);
      GuestRegularFile.Result result = GuestRegularFile.check(fs, root, SQLITE, null);
      assertFalse(target, result.regular());
      assertEquals(target, "LINK_EXTERNAL_BINDING", result.failure);
    }
  }

  @Test
  public void danglingLoopingAndNonFileTargetsAreReportedPrecisely() throws IOException {
    File root = rootfs();
    link(root, SQLITE, "libsqlite3.so.0.8.6");
    assertEquals("MISSING", GuestRegularFile.check(fs, root, SQLITE, null).failure);

    link(root, "usr/lib/aarch64-linux-gnu/a", "b");
    link(root, "usr/lib/aarch64-linux-gnu/b", "a");
    GuestRegularFile.Result loop =
        GuestRegularFile.check(fs, root, "usr/lib/aarch64-linux-gnu/a", null);
    assertEquals("LINK_LOOP", loop.failure);
    assertEquals(GuestRegularFile.MAX_LINKS + 1, loop.links);

    Files.createDirectories(root.toPath().resolve("usr/lib/aarch64-linux-gnu/dir"));
    link(root, "usr/lib/aarch64-linux-gnu/to-dir", "dir");
    assertEquals(
        "DIRECTORY",
        GuestRegularFile.check(fs, root, "usr/lib/aarch64-linux-gnu/to-dir", null).failure);

    write(root, "usr/lib/aarch64-linux-gnu/plain", "x");
    link(root, "usr/lib/aarch64-linux-gnu/through-file", "plain/child");
    assertEquals(
        "PARENT_FILE",
        GuestRegularFile.check(fs, root, "usr/lib/aarch64-linux-gnu/through-file", null).failure);

    assertEquals(
        "MISSING", GuestRegularFile.check(fs, root, "root/.dsh/plugin-manager.py", null).failure);
  }

  @Test
  public void linkedParentDirectoriesResolveInsideTheGuest() throws IOException {
    // Ubuntu 合并 /usr：lib -> usr/lib；父目录链接同样按 guest 解析。
    File root = rootfs();
    write(root, "usr/lib/aarch64-linux-gnu/libreadline.so.8.2", "rl");
    link(root, "usr/lib/aarch64-linux-gnu/libreadline.so.8", "libreadline.so.8.2");
    link(root, "lib", "usr/lib");
    GuestRegularFile.Result result =
        GuestRegularFile.check(fs, root, "lib/aarch64-linux-gnu/libreadline.so.8", null);
    assertTrue(result.failure, result.regular());
    assertEquals(2, result.links);
  }

  @Test
  public void executableModeComesFromTheResolvedTarget() throws IOException {
    File root = rootfs();
    Path python = write(root, "usr/bin/python3.12", "elf");
    try {
      Files.setPosixFilePermissions(
          python, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (UnsupportedOperationException windows) {
      return;
    }
    link(root, "usr/bin/python3", "python3.12");
    GuestRegularFile.Result result = GuestRegularFile.check(fs, root, "usr/bin/python3", null);
    assertTrue(result.failure, result.regular());
    assertEquals(0111, result.node.mode & 0111);
  }

  @Test
  public void requestedPathsMustBeRelativeAndNormalized() throws IOException {
    File root = rootfs();
    for (String bad :
        new String[] {
          "", "/usr/bin/python3.12", "usr/../etc/passwd", "usr//bin", "./usr", "usr\\bin"
        })
      try {
        GuestRegularFile.check(fs, root, bad, null);
        fail(bad);
      } catch (IOException expected) {
        assertEquals("GUEST_FILE_PATH", expected.getMessage());
      }
  }
}
