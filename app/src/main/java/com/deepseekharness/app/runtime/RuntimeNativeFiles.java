package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 在启动器使用前准备 proot 的签名原生依赖。 */
final class RuntimeNativeFiles {
  private static final Object PREPARE_LOCK = new Object();
  private final Context context;
  private final File base, lib, tmp, nativeDirectory;

  RuntimeNativeFiles(Context context, File base, File lib, File tmp) {
    this.context = context;
    this.base = base;
    this.lib = lib;
    this.tmp = tmp;
    nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
  }

  File find(String name) {
    if (com.deepseekharness.app.BuildConfig.LOW_ANDROID) {
      if (name.equals("libproot.so")) name = "libproot_legacy.so";
      else if (name.equals("libprootloader.so")) name = "libprootloader_legacy.so";
    }
    File direct = new File(nativeDirectory, name);
    if (direct.isFile()) return direct;
    File parent = nativeDirectory.getParentFile();
    File[] entries = parent == null ? null : parent.listFiles();
    if (entries != null)
      for (File directory : entries) {
        File file = new File(directory, name);
        if (directory.isDirectory() && file.isFile()) return file;
      }
    return direct;
  }

  void prepare() throws IOException {
    synchronized (PREPARE_LOCK) {
      // 发布前核对框架目录别名及所有子路径。
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      var host =
          com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
              fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
      for (File directory : new File[] {base, lib, tmp}) {
        String relative = RuntimeAssetFiles.relative(directory, context.getFilesDir(), host.files);
        fs.parents(host.files, relative + "/.directory-probe");
        File physical = fs.child(host.files, relative);
        if (!fs.stat(physical).type.equals("DIRECTORY"))
          throw new IOException("NATIVE_DEPENDENCY_DIRECTORY");
      }
      copy(find("libtalloc.so"), new File(lib, "libtalloc.so.2"));
      copy(find("libandroidshmem.so"), new File(lib, "libandroid-shmem.so"));
      host.verify(fs);
    }
  }

  private void copy(File source, File target) throws IOException {
    if (!source.isFile() || source.length() < 1 || source.length() > 16 * 1024 * 1024)
      throw new IOException("NATIVE_LIBRARY_SOURCE_INVALID:" + source.getName());
    byte[] bytes;
    try (InputStream input = new FileInputStream(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (output.size() + count > 16 * 1024 * 1024) throw new IOException("NATIVE_LIBRARY_LIMIT");
        output.write(buffer, 0, count);
      }
      bytes = output.toByteArray();
    }
    RuntimeAssetFiles.write(context, target, bytes, true);
  }
}
