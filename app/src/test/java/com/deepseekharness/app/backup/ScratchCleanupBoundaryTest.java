package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 用真实 JVM 文件系统跑清理边界：只删显式白名单，绝不碰用户数据与别人的临时文件。 */
public final class ScratchCleanupBoundaryTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();
  private final List<String> actions = new ArrayList<>();

  private ScratchCleanup.Sink sink() {
    return (action, relative, bytes, reason) ->
        actions.add(action + ":" + relative + (reason.isEmpty() ? "" : ":" + reason));
  }

  private static File directory(File parent, String relative) throws IOException {
    File file = new File(parent, relative);
    assertTrue(file.getPath(), file.mkdirs() || file.isDirectory());
    return file;
  }

  private static File write(File file, String text) throws IOException {
    Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  public void removesOnlyDeclaredTargetsAndKeepsUserData() throws IOException {
    File rootfs = temporary.newFolder("rootfs");
    File userData = temporary.newFolder("user-Documents");
    write(new File(userData, "chat-log.json"), "user");
    File scratch = directory(rootfs, "root/.cache/dsha");
    write(new File(scratch, "draft.js"), "probe");
    write(new File(directory(scratch, "sub"), "notes.md"), "draft");
    Files.createSymbolicLink(new File(scratch, "escape").toPath(), userData.toPath());
    write(new File(directory(rootfs, "root/Documents"), "keep.txt"), "user");
    File tmp = directory(rootfs, "tmp");
    write(new File(tmp, "dsha-tool-script.abc"), "owned");
    write(new File(tmp, "dsh-subprocess-1-stdout.log"), "foreign");
    write(new File(tmp, ".proroot-empty-dri"), "foreign");
    directory(tmp, "node-compile-cache");

    long freed = ScratchCleanup.run(fs, rootfs, sink());

    assertTrue(actions.toString(), actions.contains("deleted:root/.cache/dsha"));
    assertTrue(actions.toString(), actions.contains("deleted:tmp/dsha-tool-script.abc"));
    assertFalse(new File(scratch, "draft.js").exists());
    assertFalse(new File(scratch, "sub").exists());
    // 链接只删链接本身，链接目标里的用户数据必须原样保留。
    assertFalse(
        Files.exists(
            new File(scratch, "escape").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    assertTrue(new File(userData, "chat-log.json").isFile());
    assertEquals("user", Files.readString(new File(userData, "chat-log.json").toPath()));
    assertTrue(new File(rootfs, "root/Documents/keep.txt").isFile());
    assertFalse(new File(tmp, "dsha-tool-script.abc").exists());
    assertTrue(new File(tmp, "dsh-subprocess-1-stdout.log").isFile());
    assertTrue(new File(tmp, ".proroot-empty-dri").isFile());
    assertTrue(new File(tmp, "node-compile-cache").isDirectory());
    assertTrue(freed > 0);
    assertEquals(2, actions.size());
  }

  @Test
  public void symlinkedScratchDirectoryIsSkippedWithoutTouchingItsTarget() throws IOException {
    File rootfs = temporary.newFolder("rootfs");
    File userData = temporary.newFolder("user-Documents");
    write(new File(userData, "must-keep.json"), "user");
    directory(rootfs, "root/.cache");
    Files.createSymbolicLink(new File(rootfs, "root/.cache/dsha").toPath(), userData.toPath());

    ScratchCleanup.run(fs, rootfs, sink());

    assertTrue(actions.toString(), actions.contains("skipped:root/.cache/dsha:SCRATCH_PATH_LINK"));
    assertTrue(Files.isSymbolicLink(new File(rootfs, "root/.cache/dsha").toPath()));
    assertTrue(new File(userData, "must-keep.json").isFile());
  }

  @Test
  public void missingDirectoryIsAbsentAndOwnedTemporaryStillCleaned() throws IOException {
    File rootfs = temporary.newFolder("rootfs");
    directory(rootfs, "root/.cache");
    write(new File(directory(rootfs, "tmp"), "dsha-tool-script.zzz"), "owned");

    long freed = ScratchCleanup.run(fs, rootfs, sink());

    assertTrue(actions.toString(), actions.contains("absent:root/.cache/dsha"));
    assertTrue(actions.toString(), actions.contains("deleted:tmp/dsha-tool-script.zzz"));
    assertTrue(freed > 0);
  }

  @Test
  public void symlinkedTemporaryDirectoryIsSkipped() throws IOException {
    File rootfs = temporary.newFolder("rootfs");
    File elsewhere = temporary.newFolder("elsewhere");
    write(new File(elsewhere, "dsha-tool-script.outside"), "foreign");
    Files.createSymbolicLink(new File(rootfs, "tmp").toPath(), elsewhere.toPath());

    ScratchCleanup.run(fs, rootfs, sink());

    assertTrue(
        actions.toString(), actions.contains("skipped:root/.cache/dsha:SCRATCH_PARENT_TYPE"));
    assertTrue(actions.toString(), actions.contains("skipped:tmp:SCRATCH_PARENT_LINK"));
    assertTrue(new File(elsewhere, "dsha-tool-script.outside").isFile());
  }
}
