package com.deepseekharness.app.runtime;

import android.content.Context;
import android.util.Log;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.IOException;

/** 传输的整个 lease 绑定框架授权根及本次实际 guest 数据映射。 */
final class RuntimeDataTransfers {
  private final Context context;
  private final File rootfsDir;

  RuntimeDataTransfers(Context context, File rootfs) {
    this.context = context;
    rootfsDir = rootfs;
  }

  /** 把本地文件推入容器（containerPath 为容器内绝对路径，如 /root/.dsh/import-upload.bin）。 */
  public boolean pushFileIntoContainer(java.io.File src, String containerPath) {
    if (src == null || containerPath == null) return false;
    try (com.deepseekharness.app.util.RuntimeWorkPort.Work work =
        com.deepseekharness.app.util.RuntimeWorkPort.begin()) {
      containerFile(containerPath).push(src);
      return true;
    } catch (Throwable e) {
      Log.w(
          "DSHA",
          com.deepseekharness.app.util.UiText.format(
              "推文件进容器失败: %s", SensitiveData.redact(String.valueOf(e))));
      return false;
    }
  }

  /** 从容器取出文件到本地（containerPath 为容器内绝对路径）。 */
  public boolean pullFileFromContainer(String containerPath, java.io.File dest) {
    if (containerPath == null || dest == null) return false;
    try (com.deepseekharness.app.util.RuntimeWorkPort.Work work =
        com.deepseekharness.app.util.RuntimeWorkPort.begin()) {
      containerFile(containerPath).pull(dest);
      return true;
    } catch (Throwable e) {
      Log.w(
          "DSHA",
          com.deepseekharness.app.util.UiText.format(
              "从容器取文件失败: %s", SensitiveData.redact(String.valueOf(e))));
      return false;
    }
  }

  /** Bind one transfer to framework-authorized roots and this selected guest data mapping. */
  private com.deepseekharness.app.util.ContainerFileTransfers containerFile(String containerPath)
      throws IOException {
    var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    var authority =
        com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
            fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
    File physical = RuntimeAssetFiles.root(context, rootfsDir);
    var rootIdentity = fs.stat(physical);
    var layout = new com.deepseekharness.app.backup.UserDataLayout(fs, authority.files);
    var bindings = layout.binds(physical);
    String selected = java.util.Arrays.deepToString(bindings.toArray());
    File declaredCache = context.getCacheDir();
    if (!declaredCache.getParentFile().getCanonicalFile().equals(authority.files.getParentFile()))
      throw new IOException("TRANSFER_CACHE_AUTHORITY");
    File cache = fs.child(authority.files.getParentFile(), declaredCache.getName());
    if (!fs.stat(cache).type.equals("DIRECTORY")) throw new IOException("TRANSFER_CACHE_ROOT");
    File declaredStorage = android.os.Environment.getExternalStorageDirectory(),
        storage = declaredStorage.getCanonicalFile();
    java.util.List<File[]> aliases = new java.util.ArrayList<>();
    aliases.add(new File[] {context.getFilesDir(), authority.files});
    aliases.add(new File[] {declaredCache, cache});
    aliases.add(new File[] {declaredStorage, storage});
    aliases.add(new File[] {new File("/sdcard"), storage});
    aliases.add(new File[] {new File("/storage/self/primary"), storage});
    return new com.deepseekharness.app.util.ContainerFileTransfers(
        fs,
        physical,
        storage,
        bindings,
        aliases,
        java.util.Arrays.asList(authority.files, cache, storage),
        containerPath,
        () -> {
          authority.verify(fs);
          var current = fs.stat(physical);
          if (!current.type.equals("DIRECTORY")
              || current.device != rootIdentity.device
              || !current.key.equals(rootIdentity.key)
              || !selected.equals(java.util.Arrays.deepToString(layout.binds(physical).toArray())))
            throw new IOException("TRANSFER_DOMAIN_CHANGED");
        });
  }
}
