package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 使用真实私有夹具和原有 guest 映射，不读取现用数据或连接 Android 设备。 */
public final class OneClickBackupTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  @Test
  public void fixedApplicationSelectionIncludesOnlyCurrentPrivateWorkspace() throws Exception {
    File root = temporary.newFolder("rootfs"), pub = temporary.newFolder("public");
    File workspace = new File(root, "root/deepseek-harness");
    assertTrue(workspace.mkdirs());
    Files.writeString(new File(workspace, "user-work.txt").toPath(), "owned fixture");
    var selection = OneClickBackup.selection(fs, root, pub, "/root/deepseek-harness");
    assertEquals("application", selection.scope);
    assertFalse(selection.includeApiKey);
    assertEquals(List.of("/root/deepseek-harness"), selection.guestProjects);
    assertTrue(selection.documentProjects.isEmpty());
    assertEquals("", selection.retainedKey);
    assertEquals("owned fixture", Files.readString(new File(workspace, "user-work.txt").toPath()));
  }

  @Test
  public void explicitExternalWorkspacesAreNeverAutomaticallyAddedOrScanned() throws Exception {
    File root = temporary.newFolder("rootfs"), pub = temporary.newFolder("public");
    for (String path : List.of("/sdcard/project", "/storage/emulated/0/private", "/mnt/media/work"))
      assertTrue(OneClickBackup.selection(fs, root, pub, path).guestProjects.isEmpty());
    assertEquals(0, pub.list().length);
  }

  @Test
  public void relativeWorkspaceUsesGuestRootAndMissingWorkspaceIsNotInvented() throws Exception {
    File root = temporary.newFolder("rootfs"), pub = temporary.newFolder("public");
    assertTrue(new File(root, "root/relative").mkdirs());
    assertEquals(
        List.of("/root/relative"),
        OneClickBackup.selection(fs, root, pub, "relative").guestProjects);
    assertTrue(OneClickBackup.selection(fs, root, pub, "/root/missing").guestProjects.isEmpty());
    assertFalse(new File(root, "root/missing").exists());
  }

  @Test
  public void applicationDataIsNotDuplicatedAsAProjectAndBroadRootsAreRejected() throws Exception {
    File root = temporary.newFolder("rootfs"), pub = temporary.newFolder("public");
    assertTrue(
        OneClickBackup.selection(fs, root, pub, "/root/.dsh/sessions").guestProjects.isEmpty());
    for (String path : List.of("/", "/root", "/root/", "/usr", "/etc"))
      assertThrows(IOException.class, () -> OneClickBackup.selection(fs, root, pub, path));
  }

  @Test
  public void nonDirectoryWorkspaceCannotBeSilentlyCalledBackedUp() throws Exception {
    File root = temporary.newFolder("rootfs"), pub = temporary.newFolder("public");
    File source = new File(root, "root/not-directory");
    source.getParentFile().mkdirs();
    Files.writeString(source.toPath(), "data");
    assertThrows(
        IOException.class, () -> OneClickBackup.selection(fs, root, pub, "/root/not-directory"));
    assertEquals("data", Files.readString(source.toPath()));
  }

  @Test
  public void qualityAndCancellationNeverTurnIntoCompleteSuccess() {
    for (String result :
        List.of(
            "WRITTEN_UNVERIFIED",
            "PARTIAL_RESCUE",
            "BEST_EFFORT_RESCUE",
            "DATA_SAVED_PLUGIN_WARNINGS")) {
      String text =
          OneClickBackup.describe(
              new NativeBackupJobs.State("id", "FINISHED", result, "", "", 1, 2, false));
      assertFalse(text.isEmpty());
      assertFalse(text.contains("Backup complete;"));
      assertFalse(text.contains("备份完成，"));
    }
    assertTrue(
        OneClickBackup.describe(
                    new NativeBackupJobs.State("id", "CANCELLED", "", "", "", 0, 0, false))
                .contains("cancelled")
            || OneClickBackup.describe(
                    new NativeBackupJobs.State("id", "CANCELLED", "", "", "", 0, 0, false))
                .contains("取消"));
  }

  @Test
  public void failureAlwaysWinsOverAStaleSuccessResult() {
    String text =
        OneClickBackup.describe(
            new NativeBackupJobs.State("id", "FAILED", "COMPLETE", "NO_SPACE", "", 0, 0, false));
    assertTrue(text.contains("NO_SPACE"));
    assertFalse(text.contains("Backup complete;"));
    assertFalse(text.contains("备份完成，"));
  }
}
