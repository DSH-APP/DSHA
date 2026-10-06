package com.deepseekharness.app.runtime;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.function.BiConsumer;

/** Owns cold-install recovery, candidate publication and selection rollback. */
final class ColdBundleTransaction {
  private ColdBundleTransaction() {}

  static void run(ProotBootstrap boot, BiConsumer<Long, Long> onProgress) throws IOException {
    try (RuntimeHostPorts.Scope invocation = boot.hostPorts().open()) {
      boot.hostPorts()
          .stage(
              com.deepseekharness.app.util.UiText.choose(
                  "检查安装条件", "Checking installation prerequisites"));
      try {
        if (boot.coldCandidate != null) throw new IOException("COLD_CANDIDATE_REENTRY");
        Context ctx = boot.ctx;
        var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
        String appData = ctx.getApplicationInfo().dataDir;
        if (appData == null) throw new IOException("COLD_PACKAGE_HOST_ROOT");
        var host =
            com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
                fs, new File(appData), ctx.getFilesDir());
        ProotBootstrap.requireOwnedColdFiles(host.files);
        com.deepseekharness.app.backup.ColdInstallTransaction.recover(
            fs, host.files, boot::requireColdClosed, boot.hostPorts()::restoreColdSelection);
        if (com.deepseekharness.app.util.MaintenanceGate.shared().isOwner()) {
          var rebuild =
              com.deepseekharness.app.backup.EnvironmentRebuildTransaction.pending(fs, host.files);
          if (rebuild != null) {
            File owner = fs.child(host.files, "linux/.maintenance-owner");
            if (!fs.stat(owner).type.equals("FILE")
                || !rebuild
                    .directory()
                    .getName()
                    .equals(
                        new String(
                            fs.small(owner, 128), java.nio.charset.StandardCharsets.US_ASCII)))
              throw new IOException("ENVIRONMENT_REBUILD_OWNER");
            boot.extractOfflineBundleInternal(onProgress);
            return;
          }
        }
        if (com.deepseekharness.app.backup.HostMaintenancePending.blocked(ctx.getFilesDir())
            && !com.deepseekharness.app.util.MaintenanceGate.shared().isOwner())
          throw new IOException("HOST_MAINTENANCE_PENDING");
        if (host.files.getUsableSpace() < boot.expandedEnvironmentBytes())
          throw new IOException("COLD_INSTALL_SPACE_REQUIRED:" + boot.expandedEnvironmentBytes());
        var transaction =
            com.deepseekharness.app.backup.ColdInstallTransaction.create(
                fs, host, boot.expectedRuntimeDescriptor().id(), null);
        ProotBootstrap staged = new ProotBootstrap(ctx, boot.forceProot, transaction.candidate());
        try {
          staged.requireColdCandidate();
          staged.extractOfflineBundleInternal(onProgress);
          staged.requireColdCandidate();
          if (staged.preparedColdRuntime == null) throw new IOException("COLD_POSTCHECK_REQUIRED");
          transaction.selectionBefore(boot.hostPorts().snapshotColdSelection());
          boot.extractionStage(
              onProgress,
              com.deepseekharness.app.util.UiText.choose(
                  "核对完整安装文件…", "Checking complete installation files…"));
          transaction.prepared(boot::requireColdClosed);
          var mode = staged.preparedColdRuntime;
          boot.extractionStage(
              onProgress,
              com.deepseekharness.app.util.UiText.choose(
                  "确认安装文件并提交…", "Confirming and publishing installation files…"));
          transaction.publish(
              boot::requireColdClosed,
              () ->
                  boot.hostPorts()
                      .prepareColdRuntime(
                          boot.rootfsDir,
                          mode.mode,
                          mode.packageSlotSha,
                          boot.expectedRuntimeDescriptor().id()),
              boot::markOfflineExtracted,
              () -> transaction.restoreSelection(boot.hostPorts()::restoreColdSelection));
          boot.extractionStage(
              onProgress,
              com.deepseekharness.app.util.UiText.choose(
                  "安装文件已提交", "Installation files published"));
        } catch (IOException | RuntimeException failure) {
          try {
            transaction.failed(boot::requireColdClosed);
          } catch (IOException | RuntimeException uncertain) {
            failure.addSuppressed(uncertain);
          }
          throw failure;
        } finally {
          transaction.release();
        }
      } catch (IOException | RuntimeException error) {
        boot.hostPorts().failure(error);
        throw error;
      }
    }
  }
}
