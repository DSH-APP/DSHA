package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.*;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Write below the framework-authorized app root, with strict descendants and durable publication. */
final class RuntimeAssetFiles {
  private RuntimeAssetFiles() {}

  static File root(Context context, File requested) throws IOException {
    var fs = new AndroidBackupFileSystem();
    String appData = context.getApplicationInfo().dataDir;
    if (appData == null) throw new IOException("RUNTIME_ASSET_AUTHORITY");
    var host =
        com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
            fs, new File(appData), context.getFilesDir());
    File actual = fs.child(host.files, relative(requested, context.getFilesDir(), host.files));
    if (!fs.stat(actual).type.equals("DIRECTORY")) throw new IOException("RUNTIME_ASSET_ROOT_TYPE");
    host.verify(fs);
    return actual;
  }

  static void write(Context context, File file, byte[] content, boolean executable)
      throws IOException {
    var fs = new AndroidBackupFileSystem();
    String appData = context.getApplicationInfo().dataDir;
    if (appData == null) throw new IOException("RUNTIME_ASSET_AUTHORITY");
    var host =
        com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
            fs, new File(appData), context.getFilesDir());
    write(fs, host.files, relative(file, context.getFilesDir(), host.files), content, executable);
    host.verify(fs);
  }

  static String relative(File file, File declared, File actual) throws IOException {
    String path = file.getAbsolutePath();
    for (File root : new File[] {declared, actual}) {
      String prefix = root.getAbsolutePath() + File.separator;
      if (path.startsWith(prefix)) {
        String relative = path.substring(prefix.length()).replace(File.separatorChar, '/');
        if (!com.deepseekharness.app.util.ManagedInstallPath.valid(relative))
          throw new IOException("RUNTIME_ASSET_PATH");
        return relative;
      }
    }
    throw new IOException("RUNTIME_ASSET_OUTSIDE_AUTHORITY");
  }

  static void write(
      BackupFileSystem fs, File authority, String relative, byte[] content, boolean executable)
      throws IOException {
    fs.parents(authority, relative);
    File target = fs.child(authority, relative);
    var before = fs.stat(target);
    if (!before.type.equals("MISSING") && !before.type.equals("FILE"))
      throw new IOException("RUNTIME_ASSET_TARGET_TYPE");
    if (before.type.equals("FILE")
        && before.size == content.length
        && Arrays.equals(fs.small(target, content.length), content)) {
      if (executable && (before.mode & 0111) != 0111) {
        fs.mode(target, before.mode | 0111);
        fs.syncDirectory(target.getParentFile());
      }
      return;
    }
    fs.atomic(target.getParentFile(), target.getName(), content);
    if (executable) fs.mode(target, 0755);
    fs.syncDirectory(target.getParentFile());
  }
}
