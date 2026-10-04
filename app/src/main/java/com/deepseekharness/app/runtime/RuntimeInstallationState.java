package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** 负责已安装根身份、签名描述符及安装标记，不持有进程生命周期。 */
final class RuntimeInstallationState {
  private final Context ctx;
  private final File baseDir, rootfsDir, offlineMarkerFile;
  private final ManagedRuntimeAssets managedAssets;

  RuntimeInstallationState(Context ctx, File base, File root) {
    this.ctx = ctx;
    baseDir = base;
    rootfsDir = root;
    offlineMarkerFile = new File(base, ".offline-extracted");
    managedAssets =
        new ManagedRuntimeAssets(
            ctx,
            root,
            this::environmentIdentity,
            this::readAssetString,
            com.deepseekharness.app.util.BundledRootfsAsset::find);
  }

  String readAssetString(String name) {
    return RuntimeAssetText.read(ctx, name);
  }

  public boolean isOfflineExtracted() {
    return offlineMarkerFile.exists();
  }

  public boolean hasBash() {
    return RootfsReadiness.ready(rootfsDir);
  }

  /** Ubuntu 基础环境版本；仅更新受管 dsh 或重新压缩不递增。 */
  public static final String OFFLINE_VERSION_ASSET = "offline-rootfs.version";

  /** 已解压 rootfs 的版本记录文件（app 私有目录，覆盖安装保留）。 */
  private File offlineVersionFile() {
    return new File(baseDir, ".offline-version");
  }

  public String environmentIdentity() {
    return com.deepseekharness.app.util.EnvironmentIdentity.expected(
        readAssetString(OFFLINE_VERSION_ASSET).trim(),
        com.deepseekharness.app.BuildConfig.VERSION_CODE,
        com.deepseekharness.app.util.Constants.DSH_VERSION);
  }

  /** 预留解压本体、Python/pnpm 与离线基础工具的安装空间。 */
  public long expandedEnvironmentBytes() {
    try {
      long bytes = Long.parseLong(readAssetString("offline-rootfs.bytes").trim());
      if (bytes > 0 && bytes < 8L * 1024 * 1024 * 1024) return bytes + 384L * 1024 * 1024;
    } catch (RuntimeException ignored) {
    }
    return 1280L * 1024 * 1024;
  }

  /**
   * 已解压 rootfs 的版本是否与 APK 内置离线包一致。
   * 身份不一致先进入维护；同基础环境局部更新，基础环境变化才备份并重建。
   */
  public boolean rootfsVersionMatches() {
    try {
      com.deepseekharness.app.backup.RuntimeDescriptor installed = installedRuntimeDescriptor();
      return installed != null
          && installed.compatible(expectedRuntimeDescriptor())
          && com.deepseekharness.app.backup.RuntimeDescriptor.healthy(
              runtimeHealth(), installed.id());
    } catch (Throwable e) {
      return false;
    }
  }

  public boolean isEnvironmentReady() {
    try {
      var layout =
          new com.deepseekharness.app.backup.UserDataLayout(
              new com.deepseekharness.app.backup.AndroidBackupFileSystem(),
              ctx.getFilesDir().getCanonicalFile());
      if (!layout.current().isDirectory()) return false;
    } catch (IOException unavailable) {
      return false;
    }
    return isEnvironmentInstalled() && rootfsVersionMatches();
  }

  public boolean isEnvironmentInstalled() {
    return isOfflineExtracted()
        && hasBash()
        && new File(rootfsDir, "usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile();
  }

  public com.deepseekharness.app.backup.RuntimeDescriptor expectedRuntimeDescriptor()
      throws IOException {
    return managedAssets.expectedDescriptor();
  }

  public com.deepseekharness.app.backup.RuntimeDescriptor installedRuntimeDescriptor()
      throws IOException {
    return managedAssets.installedDescriptor();
  }

  public java.util.Map<String, Object> runtimeHealth() throws IOException {
    return managedAssets.health();
  }

  public boolean needsRuntimeUpdate() {
    try {
      com.deepseekharness.app.backup.RuntimeDescriptor installed = installedRuntimeDescriptor();
      return installed == null || !installed.latest(expectedRuntimeDescriptor());
    } catch (IOException error) {
      return true;
    }
  }

  public void confirmRuntimeHealth(java.util.Map<String, Object> proof) throws IOException {
    managedAssets.confirmHealth(proof);
  }

  public boolean canUpdateManagedRuntime() {
    try {
      if (!isOfflineExtracted() || !hasBash()) return false;
      String installed = Compat.readAll(new File(baseDir, ".offline-identity")).trim();
      if (!com.deepseekharness.app.util.ManagedRuntimeLayout.sameBase(
          environmentIdentity(), installed)) return false;
      // dsh 缺失或被修改正是受管更新应修复的内容，不因此升级成 Ubuntu 重建。
      return true;
    } catch (Exception error) {
      return false;
    }
  }

  /** 解压和适配全部在事务的 stage 下完成，此时既有运行时和个人目录保持原位。 */
  public List<String> stageManagedRuntime(File stage, java.util.function.Consumer<String> progress)
      throws IOException {
    return managedAssets.stage(stage, progress);
  }

  public void markOfflineExtracted() throws IOException {
    if (!baseDir.isDirectory() && !baseDir.mkdirs())
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法建立安装标记目录"));
    String identity = environmentIdentity();
    if (identity.isEmpty())
      throw new IOException(com.deepseekharness.app.util.UiText.text("APK 缺少有效环境版本，无法确认安装完成"));
    writeInstallMarker(offlineVersionFile(), readAssetString(OFFLINE_VERSION_ASSET).trim());
    writeInstallMarker(new File(baseDir, ".offline-identity"), identity);
    writeInstallMarker(offlineMarkerFile, identity);
    writeInstallMarker(
        new File(baseDir, ".runtime-descriptor.json"), readAssetString("runtime-descriptor.json"));
  }

  private void writeInstallMarker(File target, String value) throws IOException {
    ManagedRuntimeAssets.writeMarker(ctx, target, value);
  }

  /** 撤销解压标记：下次启动走 ExtractActivity 重新解压（配置保留在 .dsh，不删除）。 */
  public void markNotExtracted() {
    //noinspection ResultOfMethodCallIgnored
    offlineMarkerFile.delete();
  }
}
