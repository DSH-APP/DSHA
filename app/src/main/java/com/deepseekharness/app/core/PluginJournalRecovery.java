package com.deepseekharness.app.core;

import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.PluginInstallJournals;
import com.deepseekharness.app.backup.UserDataLayout;
import com.deepseekharness.app.util.FileKind;
import com.deepseekharness.app.util.GuestCommandOutcome;
import com.deepseekharness.app.util.PluginOutput;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Host coordination of guest plugin recovery; the backup journal itself only manages records. */
public final class PluginJournalRecovery {
  private PluginJournalRecovery() {}

  public static String recover(HarnessController controller) throws Exception {
    if (!MaintenanceCoordinator.isOwner() || RuntimeTasks.hasOtherTasks())
      throw new IOException("PLUGIN_RECOVERY_REQUIRES_MAINTENANCE");
    controller.proot().ensureRuntimeFiles();
    var fs = new AndroidBackupFileSystem();
    String id = UUID.randomUUID().toString();
    File root = controller.proot().getRootfsDir().getCanonicalFile();
    File proof = new File(root, "root/.dsha-plugin-recovery-" + id);
    try {
      try (OutputStream output = fs.create(proof)) {
        output.write("RECOVER_PLUGIN_OPERATIONS".getBytes(StandardCharsets.US_ASCII));
      }
      String text =
          GuestCommandOutcome.requireCompleted(
              controller
                  .proot()
                  .execAndReadWithProotResult(
                      "python3 /root/.dsh/plugin-manager.py recover-installs " + ShellQuote.arg(id),
                      120000),
              "PLUGIN_RECOVERY");
      var result =
          BackupJson.read(
              PluginOutput.resultJson(text).getBytes(StandardCharsets.UTF_8), 1024 * 1024);
      if (!"ok".equals(result.get("status"))) {
        Object message = result.get("message");
        throw new IOException(
            message instanceof String ? (String) message : "PLUGIN_RECOVERY_FAILED");
      }
      PluginInstallJournals.archiveCompleted(
          fs,
          new UserDataLayout(fs, root.getParentFile().getParentFile().getCanonicalFile())
              .current());
      return com.deepseekharness.app.util.UiText.text("中断的插件操作已恢复，旧版本、依赖与失败候选均保留。");
    } finally {
      if (fs.stat(proof).kind != FileKind.MISSING) fs.delete(proof);
    }
  }
}
