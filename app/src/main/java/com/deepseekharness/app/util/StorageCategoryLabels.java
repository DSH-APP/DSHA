package com.deepseekharness.app.util;

import java.util.Map;

/** Display identity follows storage keys; unknown categories remain visible as their original key. */
public final class StorageCategoryLabels {
  private StorageCategoryLabels() {}

  private static final Map<String, String[]> LABELS =
      Map.of(
          "linux", new String[] {"当前 Linux 与 DSH", "Current Linux and DSH"},
          "host-runtime-operations", new String[] {"运行时回退副本", "Runtime rollback copies"},
          "host-environment-operations", new String[] {"环境重建保护副本", "Environment protection copies"},
          "runtime-updates", new String[] {"旧版更新记录", "Legacy update records"},
          "host-backup-operations", new String[] {"备份副本", "Backup copies"},
          "mozilla", new String[] {"兼容浏览器数据", "Compatibility browser data"},
          "user-data-v5", new String[] {"宿主个人数据", "Persistent user data"},
          "cache", new String[] {"应用缓存", "App cache"});

  public static String label(String key, boolean english) {
    if (key == null) return null;
    String[] names = LABELS.get(key);
    return names == null ? key : names[english ? 1 : 0];
  }
}
