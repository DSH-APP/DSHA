package com.deepseekharness.app.runtime;

import java.io.*;
import java.util.*;
import com.deepseekharness.app.backup.*;

/** guest 数据绑定和受管脚本覆盖使用同一清单；不改变历史 L2S 的宿主绝对路径绑定。 */
final class UserDataBindings {
  private UserDataBindings() {}

  static void append(List<String> argv, File rootfs, File filesDomain) {
    append(argv, rootfs, filesDomain, new AndroidBackupFileSystem());
  }

  static void append(List<String> argv, File rootfs, File filesDomain, BackupFileSystem fs) {
    try {
      if (!fs.stat(filesDomain).type.equals("DIRECTORY"))
        throw new IOException("DATA_DOMAIN_FILES_TYPE");
      File files = filesDomain.getCanonicalFile(), expected = fs.child(files, "linux/ubuntu");
      if (!fs.stat(expected).type.equals("DIRECTORY")
          || !rootfs.getCanonicalFile().equals(expected.getCanonicalFile()))
        throw new IOException("DATA_DOMAIN_ROOTFS_MISMATCH");
      for (String[] bind : new UserDataLayout(fs, files).binds(rootfs)) {
        argv.add("-b");
        argv.add(bind[0] + ":" + bind[1]);
      }
      // 本机回执与独立快照位于 rootfs 外，不接受用户归档携带的状态。
      File migration = fs.child(files, "rc1-migration-state");
      var node = fs.stat(migration);
      if (node.type.equals("MISSING")) fs.directory(migration);
      else if (!node.type.equals("DIRECTORY")) throw new IOException("MIGRATION_STATE_UNAVAILABLE");
      argv.add("-b");
      argv.add(migration.getAbsolutePath() + ":/run/dsha-rc1-state");
    } catch (IOException error) {
      throw new IllegalStateException("DATA_LOCATION_UNREADABLE", error);
    }
  }
}
