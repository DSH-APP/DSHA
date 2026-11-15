package com.deepseekharness.app.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;

/** 固定受管文件的进程内准备戳；只合并元数据读取，不替代首次内容校验。 */
final class RuntimeAssetStamp {
  private RuntimeAssetStamp() {}

  interface Stats {
    StructStat lstat(File file) throws ErrnoException;
  }

  static String read(File root, Iterable<File> files) {
    return inspect(root, files).value;
  }

  static String read(File root, Iterable<File> files, Stats stats) {
    return inspect(root, files, stats).value;
  }

  static final class Snapshot {
    final String value;
    final int unavailableEntry;

    Snapshot(String value, int unavailableEntry) {
      this.value = value;
      this.unavailableEntry = unavailableEntry;
    }
  }

  static Snapshot inspect(File root, Iterable<File> files) {
    return inspect(root, files, file -> Os.lstat(file.getAbsolutePath()));
  }

  static Snapshot inspect(File root, Iterable<File> files, Stats stats) {
    int at = -1;
    try {
      StructStat directory = stats.lstat(root);
      if (!OsConstants.S_ISDIR(directory.st_mode)) return new Snapshot(null, at);
      StringBuilder value = new StringBuilder();
      value.append(directory.st_dev).append(':').append(directory.st_ino);
      for (File file : files) {
        at++;
        // 一次 NOFOLLOW 读取同时判定类型与身份，避免 isFile、isSymbolicLink、
        // lstat、lastModified 各读一次，也不混用不同读取时点的元数据。
        StructStat stat = stats.lstat(file);
        if (!OsConstants.S_ISREG(stat.st_mode)) return new Snapshot(null, at);
        value
            .append('|')
            .append(stat.st_dev)
            .append(':')
            .append(stat.st_ino)
            .append(':')
            .append(stat.st_size)
            .append(':')
            .append(stat.st_mtim.tv_sec)
            .append(':')
            .append(stat.st_mtim.tv_nsec)
            .append(':')
            .append(stat.st_ctim.tv_sec)
            .append(':')
            .append(stat.st_ctim.tv_nsec)
            .append(':')
            .append(stat.st_mode);
      }
      return new Snapshot(value.toString(), -3);
    } catch (ErrnoException unavailable) {
      // 缺失、权限错误及其它读取失败均让缓存失效，交回完整准备路径。
      return new Snapshot(null, at);
    }
  }

  /** Diagnostic only: -1 denotes the root, -2 unavailable input, otherwise a managed-file index. */
  static int firstDifference(String expected, String observed) {
    if (expected == null || observed == null) return -2;
    String[] before = expected.split("\\|", -1), after = observed.split("\\|", -1);
    for (int at = 0; at < Math.min(before.length, after.length); at++)
      if (!before[at].equals(after[at])) return at - 1;
    if (before.length != after.length) return Math.min(before.length, after.length) - 1;
    return -3;
  }
}
