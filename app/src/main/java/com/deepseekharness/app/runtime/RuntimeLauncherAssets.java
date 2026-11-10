package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.*;
import java.io.*;
import java.util.*;

/** APK launcher overlays live outside retained runtime trees, which remain byte-for-byte intact. */
final class RuntimeLauncherAssets {
  static final String GUEST = "/run/dsha-launcher";
  private static final Object LOCK = new Object();

  private RuntimeLauncherAssets() {}

  static File prepare(Context context) throws IOException {
    return prepare(context, RuntimeApkAssets.get(context));
  }

  static File prepare(Context context, RuntimeApkAssets.Snapshot apk) throws IOException {
    synchronized (LOCK) {
      var fs = new AndroidBackupFileSystem();
      String data = context.getApplicationInfo().dataDir;
      if (data == null) throw new IOException("LAUNCHER_ASSET_CONTEXT");
      var host =
          com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
              fs, new File(data), context.getFilesDir());
      File parent = fs.child(host.files, "runtime-launcher-assets");
      if (fs.stat(parent).type.equals("MISSING")) fs.directory(parent);
      if (!fs.stat(parent).type.equals("DIRECTORY")) throw new IOException("LAUNCHER_ASSET_ROOT");
      Map<String, byte[]> bytes = apk.preloads;
      String id = apk.preloadId;
      File target = fs.child(parent, id);
      if (current(fs, target, bytes)) {
        host.verify(fs);
        return target;
      }
      if (!fs.stat(target).type.equals("MISSING"))
        target = fs.child(parent, id + "-" + UUID.randomUUID());
      fs.directory(target);
      for (var entry : bytes.entrySet()) fs.atomic(target, entry.getKey(), entry.getValue());
      host.verify(fs);
      if (!current(fs, target, bytes)) throw new IOException("LAUNCHER_ASSET_VERIFICATION");
      return target;
    }
  }

  private static boolean current(BackupFileSystem fs, File root, Map<String, byte[]> expected)
      throws IOException {
    if (!fs.stat(root).type.equals("DIRECTORY")) return false;
    if (!new HashSet<>(fs.list(root)).equals(expected.keySet())) return false;
    for (var entry : expected.entrySet()) {
      File file = fs.child(root, entry.getKey());
      if (!fs.stat(file).type.equals("FILE")) return false;
      if (!Arrays.equals(fs.small(file, 512 * 1024), entry.getValue())) return false;
    }
    return true;
  }
}
