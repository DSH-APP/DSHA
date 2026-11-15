package com.deepseekharness.app.util;

/**
 * 保留副本清单的类型筛选：只决定显示哪些行，不改变选择、检查或任何原件。
 *
 * <p>按 RetainedCatalogue.Kind 的名称分组，未知名称一律归入「其他」，新增类型不会被筛掉。
 */
public final class RetainedFilter {
  public static final int ALL = 0, BACKUP = 1, ENVIRONMENT = 2, PLUGIN = 3, OTHER = 4;
  public static final int COUNT = 5;

  private RetainedFilter() {}

  public static int group(String kind) {
    if (kind == null) return OTHER;
    return switch (kind) {
      case "BACKUP", "RESTORE" -> BACKUP;
      case "ENVIRONMENT", "RUNTIME" -> ENVIRONMENT;
      case "PLUGIN", "QUARANTINE" -> PLUGIN;
      default -> OTHER;
    };
  }

  public static boolean matches(int filter, String kind) {
    return filter == ALL || group(kind) == filter;
  }
}
