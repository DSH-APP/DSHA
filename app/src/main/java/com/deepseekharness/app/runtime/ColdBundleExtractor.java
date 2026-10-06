package com.deepseekharness.app.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import com.deepseekharness.app.util.ExtractionReadProgress;

/** Extracts signed cold assets and prepares the candidate before publication. */
final class ColdBundleExtractor {
  private ColdBundleExtractor() {}

  static void run(ProotBootstrap boot, java.util.function.BiConsumer<Long, Long> onProgress)
      throws IOException {
    // 进程重启后旧环境可能正被维护日志保护；必须在任何目录/资产写入之前拒绝覆盖。
    if (com.deepseekharness.app.backup.HostMaintenancePending.blocked(boot.ctx.getFilesDir())
        && !com.deepseekharness.app.util.MaintenanceGate.shared().isOwner())
      throw new IOException(
          com.deepseekharness.app.util.UiText.text("上次环境维护尚未完成，请先恢复中断维护；现有目录未覆盖"));
    File[] previous = boot.rootfsDir.listFiles();
    if (boot.rootfsDir.exists() && (previous == null || previous.length != 0))
      throw new IOException(
          com.deepseekharness.app.util.UiText.text("已有运行环境，必须先通过备份和维护事务重建；禁止直接覆盖旧数据"));
    boot.extractionStage(onProgress, com.deepseekharness.app.util.UiText.text("准备解压"));
    boot.ensureRuntimeFiles();
    ZipFile apk = null;
    InputStream raw = null;
    long archiveBytes = -1;
    try {
      try {
        apk = new ZipFile(boot.ctx.getPackageCodePath());
        ZipEntry e = boot.findBundleEntry(apk);
        if (e != null) {
          raw = apk.getInputStream(e);
          archiveBytes = e.getSize();
        }
      } catch (IOException ignored) {
        if (apk != null) {
          try {
            apk.close();
          } catch (IOException ignored2) {
          }
          apk = null;
        }
      }
      if (raw == null) {
        IOException last = null;
        try {
          raw = boot.ctx.getAssets().open(com.deepseekharness.app.util.BundledRootfsAsset.NAME);
        } catch (IOException e) {
          last = e;
        }
        if (raw == null) {
          throw last != null
              ? last
              : new IOException(com.deepseekharness.app.util.UiText.text("assets 里也没有离线包"));
        }
      }

      boolean split =
          "split-runtime-v1".equals(boot.readAssetString("offline-rootfs.layout").trim());
      ZipEntry runtime = split && apk != null ? apk.getEntry("assets/dsh-runtime.bin") : null;
      if (split && apk != null && runtime == null)
        throw new IOException(com.deepseekharness.app.util.UiText.text("APK 缺少独立 dsh 运行时，安装未完成"));
      long totalBytes = archiveBytes;
      if (split)
        totalBytes =
            archiveBytes >= 0 && runtime != null && runtime.getSize() >= 0
                ? archiveBytes + runtime.getSize()
                : -1;
      ExtractionReadProgress progress = new ExtractionReadProgress(totalBytes, onProgress);

      // 入口已确认本次目标为空；已有环境只能由外层维护事务保留、发布与回切。
      boot.rootfsDir.mkdirs();
      boot.extractionStage(
          onProgress, com.deepseekharness.app.util.UiText.text("解压 Ubuntu 与 Node"));
      if (split && boot.coldCandidate != null) {
        boot.requireColdCandidate();
        try (InputStream input =
            apk == null
                ? boot.ctx.getAssets().open("dsh-runtime.bin")
                : apk.getInputStream(runtime)) {
          ColdSplitExtraction.run(
              new com.deepseekharness.app.backup.AndroidBackupFileSystem(),
              boot.rootfsDir,
              boot.coldCandidate.linux().getParentFile(),
              raw,
              input,
              totalBytes,
              onProgress,
              ColdSplitExtraction::extractSigned);
        }
        boot.requireColdCandidate();
      } else {
        TarGzipExtractor.extractAuto(progress.count(raw), boot.rootfsDir, 0);
        progress.flush();
        if (split) {
          boot.extractionStage(
              onProgress, com.deepseekharness.app.util.UiText.text("解压 dsh 与内置依赖"));
          try (InputStream input =
              apk == null
                  ? boot.ctx.getAssets().open("dsh-runtime.bin")
                  : apk.getInputStream(runtime)) {
            TarGzipExtractor.extractAuto(progress.count(input), boot.rootfsDir, 0);
            progress.flush();
          }
        }
      }
      boot.extractionStage(
          onProgress, com.deepseekharness.app.util.UiText.text("安装 Python 与 pnpm"));
      boot.installBundledPython(boot.rootfsDir);
      boot.installBundledPnpm(boot.rootfsDir);
      boot.extractionStage(onProgress, com.deepseekharness.app.util.UiText.text("准备应用工具"));
      // 本次完整补丁链与应用入口只准备一次，离线 dpkg 不安装受管 dsh 文件。
      // 仍在首次 guest 启动前完成准备；提交和后续真实运行核验照常执行。
      boot.ensureDshRuntimePatches();
      RuntimeTools.prepareBuiltinDependencies(boot.rootfsDir);
      boot.ensureAndroidGroups();
      boot.extractionStage(
          onProgress, com.deepseekharness.app.util.UiText.text("安装离线 curl、git 与证书"));
      ProotBootstrap.ColdRuntimeSelection installedRuntime =
          boot.installBundledUbuntuTools(onProgress);
      if (boot.coldCandidate == null)
        com.deepseekharness.app.util.ColdInstallPlan.publishReady(
            () ->
                boot.hostPorts()
                    .successfulColdRuntime(
                        boot.rootfsDir, installedRuntime.mode, installedRuntime.packageSlotSha),
            boot::markOfflineExtracted);
      else boot.preparedColdRuntime = installedRuntime;
      boot.extractionStage(onProgress, com.deepseekharness.app.util.UiText.text("解压与离线安装完成"));
    } finally {
      try {
        if (raw != null) raw.close();
      } finally {
        if (apk != null) apk.close();
      }
    }
  }
}
