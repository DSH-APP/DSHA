package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.BackupTree;
import com.deepseekharness.app.backup.RuntimeDescriptor;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ManagedRuntimeLayout;
import com.deepseekharness.app.util.ManagedRuntimePaths;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** APK-managed tree staging and health proof, separate from guest process execution. */
final class ManagedRuntimeAssets {
  interface AssetText {
    String read(String name);
  }

  interface LegacyArchive {
    ZipEntry find(ZipFile apk);
  }

  private final Context context;
  private final File rootfs;
  private final Supplier<String> environmentIdentity;
  private final AssetText assetText;
  private final LegacyArchive legacyArchive;

  ManagedRuntimeAssets(
      Context context,
      File rootfs,
      Supplier<String> environmentIdentity,
      AssetText assetText,
      LegacyArchive legacyArchive) {
    this.context = context;
    this.rootfs = rootfs;
    this.environmentIdentity = environmentIdentity;
    this.assetText = assetText;
    this.legacyArchive = legacyArchive;
  }

  RuntimeDescriptor expectedDescriptor() throws IOException {
    return new RuntimeDescriptor(
        BackupJson.read(
            assetText.read("runtime-descriptor.json").getBytes(StandardCharsets.UTF_8),
            2 * 1024 * 1024));
  }

  RuntimeDescriptor installedDescriptor() throws IOException {
    AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
    File files = context.getFilesDir().getCanonicalFile();
    File linux = fs.child(files, "linux");
    if (fs.stat(linux).type.equals("MISSING")) return null;
    File file = fs.child(linux, ".runtime-descriptor.json");
    if (fs.stat(file).type.equals("MISSING")) return null;
    return new RuntimeDescriptor(BackupJson.read(fs.small(file, 2 * 1024 * 1024), 2 * 1024 * 1024));
  }

  Map<String, Object> health() throws IOException {
    RuntimeDescriptor descriptor = installedDescriptor();
    if (descriptor == null) return null;
    AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
    File home = new File(context.getFilesDir().getCanonicalFile(), "runtime-health");
    File file = new File(home, descriptor.id() + ".json");
    if (fs.stat(home).type.equals("MISSING") || fs.stat(file).type.equals("MISSING")) return null;
    return BackupJson.read(fs.small(file, 512 * 1024), 512 * 1024);
  }

  void confirmHealth(Map<String, Object> proof) throws IOException {
    RuntimeDescriptor descriptor = installedDescriptor();
    if (descriptor == null || !RuntimeDescriptor.healthy(proof, descriptor.id()))
      throw new IOException("RUNTIME_HEALTH_INCOMPLETE");
    AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
    File files = context.getFilesDir().getCanonicalFile();
    File home = new File(files, "runtime-health");
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    Map<String, String> hashes = new LinkedHashMap<>();
    for (String path : installedManagedPaths())
      hashes.put(path, BackupTree.digest(fs, fs.child(files, path), new BackupControl(null)));
    proof.put("managedHashes", hashes);
    fs.atomic(home, descriptor.id() + ".json", BackupJson.write(proof, 512 * 1024));
  }

  private static final ManagedRuntimePaths.View ALIAS_VIEW =
      new ManagedRuntimePaths.View() {
        public File[] list(File directory) {
          return directory.listFiles();
        }

        public boolean symbolic(File file) {
          return Compat.isSymbolicLink(file);
        }

        public boolean exists(File file) {
          return file.exists();
        }

        public String canonical(File file) throws IOException {
          return file.getCanonicalPath();
        }
      };

  /** Only health confirmation walks the full managed tree; normal readiness reads its receipt. */
  private List<String> installedManagedPaths() throws IOException {
    return ManagedRuntimePaths.enumerate(rootfs, rootfs, ALIAS_VIEW);
  }

  List<String> stage(File stage, Consumer<String> progress) throws IOException {
    File root = new File(stage, "linux/ubuntu");
    if (!root.mkdirs()) throw new IOException(UiText.text("无法建立独立运行时暂存目录"));
    final String prefix = ManagedRuntimeLayout.DSH;
    progress.accept(UiText.text("正在解压新版 dsh（保留现有 Ubuntu 与个人目录）…"));
    try (ZipFile apk = new ZipFile(context.getPackageCodePath())) {
      boolean split = apk.getEntry("assets/offline-rootfs.layout") != null;
      ZipEntry bundle = split ? apk.getEntry("assets/dsh-runtime.bin") : legacyArchive.find(apk);
      if (bundle == null) throw new IOException(UiText.text("APK 没有内置运行时"));
      try (InputStream input = apk.getInputStream(bundle)) {
        TarGzipExtractor.extractSelected(
            input,
            root,
            0,
            name ->
                name.equals(prefix)
                    || name.startsWith(prefix + "/")
                    || ManagedRuntimeLayout.alias(name)
                    || name.equals("usr/local/share/dsha/dsh-runtime.version"));
      }
    }
    progress.accept(UiText.text("正在准备新版内置插件和界面适配…"));
    RuntimeTools.stage(context, root);
    RuntimeTools.prepareBuiltinDependencies(root);
    List<String> paths = ManagedRuntimePaths.enumerate(root, rootfs, ALIAS_VIEW);
    String identity = environmentIdentity.get();
    Map<ManagedRuntimePaths.Marker, String> values =
        Map.of(
            ManagedRuntimePaths.Marker.IDENTITY,
            identity,
            ManagedRuntimePaths.Marker.EXTRACTED,
            identity,
            ManagedRuntimePaths.Marker.VERSION,
            assetText.read("offline-rootfs.version").trim(),
            ManagedRuntimePaths.Marker.DESCRIPTOR,
            assetText.read("runtime-descriptor.json"));
    for (ManagedRuntimePaths.Marker marker : ManagedRuntimePaths.Marker.values())
      writeMarker(context, new File(stage, marker.relative), values.get(marker));
    return paths;
  }

  static void writeMarker(Context context, File target, String value) throws IOException {
    RuntimeAssetFiles.write(context, target, value.getBytes(StandardCharsets.UTF_8), false);
  }
}
