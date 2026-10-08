package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;

/** guest 临时目录不依赖工具安装脚本；只补缺失目录，保留已有内容与权限。 */
final class RuntimeTemporaryDirectories {
  private RuntimeTemporaryDirectories() {}

  static void prepare(BackupFileSystem fs, File root) throws IOException {
    var authority = fs.stat(root);
    if (!authority.type.equals("DIRECTORY")) throw new IOException("GUEST_TEMP_ROOT_TYPE");
    for (String relative : new String[] {"tmp", "var/tmp"}) {
      fs.parents(root, relative);
      File directory = fs.child(root, relative);
      var before = fs.stat(directory);
      if (before.type.equals("MISSING")) {
        fs.directory(directory);
        fs.mode(directory, 01777);
        fs.syncDirectory(directory.getParentFile());
      } else if (!before.type.equals("DIRECTORY")) {
        throw new IOException("GUEST_TEMP_DIRECTORY_TYPE:" + relative);
      }
      var ready = fs.stat(directory);
      if (!ready.type.equals("DIRECTORY") || (ready.mode & 0300) != 0300)
        throw new IOException("GUEST_TEMP_NOT_WRITABLE:" + relative);
      var now = fs.stat(root);
      if (!now.type.equals("DIRECTORY") || !authority.key.equals(now.key))
        throw new IOException("GUEST_TEMP_ROOT_CHANGED");
    }
  }
}
