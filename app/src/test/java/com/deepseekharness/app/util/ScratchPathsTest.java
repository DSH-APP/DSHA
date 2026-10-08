package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 临时文件目录的约定、显式白名单与删除前绝对路径核验。 */
public final class ScratchPathsTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  private File root() throws IOException {
    return temporary.newFolder("rootfs");
  }

  private static File directory(File parent, String name) throws IOException {
    File file = new File(parent, name);
    assertTrue(file.getPath(), file.mkdirs() || file.isDirectory());
    return file;
  }

  @Test
  public void guestAndRootfsSpellingsAgree() {
    assertEquals("/" + ScratchPaths.ROOTFS_DIRECTORY, ScratchPaths.GUEST_DIRECTORY);
    assertEquals("root/.cache/dsha", ScratchPaths.ROOTFS_DIRECTORY);
  }

  @Test
  public void declaredDirectoryIsOnTheExistingRegenerableList() {
    assertTrue(RegenerableCachePaths.rootfs().contains(ScratchPaths.ROOTFS_DIRECTORY));
    assertEquals(1, ScratchPaths.DIRECTORY_WHITELIST.size());
    assertTrue(ScratchPaths.declaredDirectory(ScratchPaths.ROOTFS_DIRECTORY));
  }

  @Test
  public void undeclaredPathsAreRefusedInsteadOfCleaned() throws IOException {
    File root = root();
    for (String relative :
        new String[] {
          "root",
          "root/.cache",
          "tmp",
          "root/Documents",
          "root/.dsh",
          "root/.dsh/sessions",
          "/root/.cache/dsha",
          "root/.cache/../..",
          "root/.cache/dsha/../.."
        }) {
      try {
        ScratchPaths.resolve(root, relative);
        fail("must refuse: " + relative);
      } catch (IOException expected) {
        assertTrue(
            relative + " → " + expected.getMessage(), expected.getMessage().contains("SCRATCH"));
      }
    }
  }

  @Test
  public void resolutionReturnsTheVerifiedAbsolutePath() throws IOException {
    File root = root();
    directory(directory(root, "root"), ".cache");
    File scratch = directory(new File(root, "root/.cache"), "dsha");
    File resolved = ScratchPaths.resolve(root, ScratchPaths.ROOTFS_DIRECTORY);
    assertEquals(scratch.getCanonicalPath(), resolved.getCanonicalPath());
    assertEquals(
        root.getCanonicalFile(),
        resolved.getParentFile().getParentFile().getParentFile().getCanonicalFile());
  }

  @Test
  public void missingOrSymlinkedParentsAreRefusedSoCleanupSkipsThem() throws IOException {
    File root = root();
    try {
      ScratchPaths.resolve(root, ScratchPaths.ROOTFS_DIRECTORY);
      fail("missing parent must be refused");
    } catch (IOException expected) {
      assertEquals("SCRATCH_PARENT_TYPE", expected.getMessage());
    }
    File cache = directory(root, "root");
    Path escape = temporary.newFolder("escape").toPath();
    Files.createSymbolicLink(new File(cache, ".cache").toPath(), escape);
    try {
      ScratchPaths.resolve(root, ScratchPaths.ROOTFS_DIRECTORY);
      fail("symlinked parent must be refused");
    } catch (IOException expected) {
      assertEquals("SCRATCH_PARENT_LINK", expected.getMessage());
    }
  }

  @Test
  public void ownedTemporaryMatchesOnlyThisAppsOwnPrefix() {
    assertTrue(ScratchPaths.ownedTemporary("dsha-tool-script.abcdef"));
    assertFalse(ScratchPaths.ownedTemporary("dsha-tool-script."));
    assertFalse(ScratchPaths.ownedTemporary("dsha-tool-script.a/b"));
    for (String foreign :
        new String[] {
          "dsh-subprocess-1-stdout.log",
          "node-compile-cache",
          ".proroot-empty-dri",
          ".proroot-proc-bus-pci-devices",
          "cand.js",
          "aapt2.jar",
          ""
        }) {
      assertFalse(foreign, ScratchPaths.ownedTemporary(foreign));
    }
  }

  @Test
  public void ownedFileVerificationRefusesEverythingElse() throws IOException {
    File root = root();
    directory(root, "tmp");
    assertTrue(ScratchPaths.declaredOwnedDirectory("tmp"));
    assertFalse(ScratchPaths.declaredOwnedDirectory("root/.cache/dsha"));
    assertEquals(
        new File(root, "tmp/dsha-tool-script.1").getPath(),
        ScratchPaths.verifyOwnedFile(root, "tmp/dsha-tool-script.1").getPath());
    for (String relative :
        new String[] {
          "tmp/other.log",
          "tmp/dsha-tool-script.",
          "tmp/dsha-tool-script.a/b",
          "root/.cache/dsha",
          "root/dsha-tool-script.1"
        }) {
      try {
        ScratchPaths.verifyOwnedFile(root, relative);
        fail("must refuse: " + relative);
      } catch (IOException expected) {
        assertEquals(relative, "SCRATCH_PATH_UNDECLARED", expected.getMessage());
      }
    }
  }

  @Test
  public void symlinkedTemporaryDirectoryIsRefused() throws IOException {
    File root = root();
    Path escape = temporary.newFolder("elsewhere").toPath();
    Files.createSymbolicLink(new File(root, "tmp").toPath(), escape);
    try {
      ScratchPaths.verifyOwnedFile(root, "tmp/dsha-tool-script.1");
      fail("symlinked tmp must be refused");
    } catch (IOException expected) {
      assertEquals("SCRATCH_PARENT_LINK", expected.getMessage());
    }
  }

  @Test
  public void shippedPluginPromptAndJavaConstantStayInSync() throws IOException {
    File asset = new File("src/main/assets/builtin-plugins/dsh-device-shell-guide/lib/index.js");
    if (!asset.isFile())
      asset = new File("app/src/main/assets/builtin-plugins/dsh-device-shell-guide/lib/index.js");
    assertTrue("内置插件提示词必须随包发布", asset.isFile());
    String source = Files.readString(asset.toPath());
    assertTrue(source, source.contains(ScratchPaths.GUEST_DIRECTORY));
    assertTrue(source, source.contains("DSHA_CACHE_DIR"));
  }
}
