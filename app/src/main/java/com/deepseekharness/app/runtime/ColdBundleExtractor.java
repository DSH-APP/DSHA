package com.deepseekharness.app.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

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

      InputStream counted = raw;
      final java.util.function.BiConsumer<Long, Long> cb = onProgress;
      final long totalBytes = archiveBytes;
      if (cb != null) {
        counted =
            new java.io.FilterInputStream(raw) {
              long done = 0;

              @Override
              public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                  done += n;
                  cb.accept(done, totalBytes);
                }
                return n;
              }
            };
      }

      // 覆盖安装换了内置包（版本不符）时，先清掉旧 rootfs 再解压，
      // 避免旧版残留文件（alpha.5 独有的 dsh 文件）与新包混在一起
      boot.rootfsDir.mkdirs();
      boot.extractionStage(
          onProgress, com.deepseekharness.app.util.UiText.text("解压 Ubuntu 与 Node"));
      TarGzipExtractor.extractAuto(counted, boot.rootfsDir, 0);
      if ("split-runtime-v1".equals(boot.readAssetString("offline-rootfs.layout").trim())) {
        boot.extractionStage(onProgress, com.deepseekharness.app.util.UiText.text("解压 dsh 与内置依赖"));
        ZipEntry runtime = apk == null ? null : apk.getEntry("assets/dsh-runtime.bin");
        if (apk != null && runtime == null)
          throw new IOException(com.deepseekharness.app.util.UiText.text("APK 缺少独立 dsh 运行时，安装未完成"));
        try (InputStream input =
            apk == null
                ? boot.ctx.getAssets().open("dsh-runtime.bin")
                : apk.getInputStream(runtime)) {
          TarGzipExtractor.extractAuto(input, boot.rootfsDir, 0);
        }
      }
      boot.extractionStage(
          onProgress, com.deepseekharness.app.util.UiText.text("安装 Python 与 pnpm"));
      boot.installBundledPython(boot.rootfsDir);
      boot.installBundledPnpm(boot.rootfsDir);
      boot.extractionStage(onProgress, com.deepseekharness.app.util.UiText.text("准备应用工具"));
      RuntimeTools.prepare(boot.ctx, boot.getRootfsDir());
      RuntimeTools.prepareBuiltinDependencies(boot.rootfsDir);
      boot.ensureAndroidGroups();
      boot.extractionStage(
          onProgress, com.deepseekharness.app.util.UiText.text("安装离线 curl、git 与证书"));
      ProotBootstrap.ColdRuntimeSelection installedRuntime =
          boot.installBundledUbuntuTools(onProgress);
      boot.extractionStage(onProgress, com.deepseekharness.app.util.UiText.text("适配 dsh 运行时"));
      boot.ensureDshRuntimePatches();
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
