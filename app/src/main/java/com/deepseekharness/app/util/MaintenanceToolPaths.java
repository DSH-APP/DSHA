package com.deepseekharness.app.util;

import java.util.Set;

/**
 * 环境重建（整树替换）与受管更新（局部注入）对 rootfs 顶层目录的明文归属。
 *
 * <p>此前 {@code MaintenanceDataSnapshot} 把“不在 SYSTEM 集合里”隐式当成用户数据；本类把同一
 * 判定写成显式契约，供文档、测试与预检共用。行为必须与历史集合逐项一致：只有 {@link #SYSTEM_ROOTS}
 * 里的顶层目录不保留；`/opt` 是用户工具根，整树重建会复制并恢复，但它是数据复制而不是受管注入。
 *
 * <p>受管路径（{@link ManagedRuntimeLayout#paths()}，含 `/opt/gh/bin/gh`）优先于数据快照：重建时由
 * APK 受管资产重新注入，不把旧字节当用户数据恢复。`/usr/local/share/dsha` 同理。
 */
public final class MaintenanceToolPaths {
  private MaintenanceToolPaths() {}

  /** 系统树：整树重建时被新 rootfs 替换；用户放在这里的脚本/符号链接不会保留。 */
  public static final Set<String> SYSTEM_ROOTS =
      Set.of(
          "bin",
          "sbin",
          "lib",
          "lib64",
          "usr",
          "etc",
          "var",
          "tmp",
          "run",
          "proc",
          "dev",
          "sys",
          "root",
          ".l2s",
          "lost+found",
          "sdcard",
          "storage",
          "system",
          "apex");

  /** 用户自装工具根：环境重建按数据快照复制并恢复（含 3.5GB/48,294 条目的实测成本）。 */
  public static final Set<String> USER_TOOL_ROOTS = Set.of("opt");

  public enum Kind {
    /** APK 受管路径：受管更新替换，整树重建后重新注入。 */
    MANAGED,
    /** 系统树：整树重建后是全新内容，用户内容不保留。 */
    SYSTEM,
    /** 用户工具根：整树重建按数据复制保留。 */
    USER_TOOL,
    /** 其他个人数据：整树重建按数据复制保留。 */
    DATA
  }

  /** rootfs 相对路径是否受管；与 {@link ManagedRuntimeLayout#paths()} 单点同源。 */
  public static boolean managed(String relative) {
    if (relative == null || relative.isEmpty()) return false;
    for (String path : ManagedRuntimeLayout.paths())
      if (relative.equals(path) || relative.startsWith(path + "/")) return true;
    return false;
  }

  /** 顶层目录是否属于系统树。 */
  public static boolean system(String relative) {
    if (relative == null || relative.isEmpty()) return false;
    int slash = relative.indexOf('/');
    return SYSTEM_ROOTS.contains(slash < 0 ? relative : relative.substring(0, slash));
  }

  /** 顶层目录是否属于用户工具根。 */
  public static boolean userTool(String relative) {
    if (relative == null || relative.isEmpty() || managed(relative)) return false;
    int slash = relative.indexOf('/');
    return USER_TOOL_ROOTS.contains(slash < 0 ? relative : relative.substring(0, slash));
  }

  public static Kind classify(String relative) {
    if (relative == null || relative.isEmpty()) return Kind.DATA;
    if (managed(relative)) return Kind.MANAGED;
    // /root 本身是系统树（顶层不作为整体复制），但它的子项由 select 逐项作为数据保留；
    // .dsh 数据根与机器状态排除另由 UserDataLayout / machine() 处理。
    if (relative.equals("root") || relative.startsWith("root/")) return Kind.DATA;
    if (system(relative)) return Kind.SYSTEM;
    if (userTool(relative)) return Kind.USER_TOOL;
    return Kind.DATA;
  }

  /** 整树重建时是否由数据快照恢复（受管与系统树返回 false）。 */
  public static boolean preservedByRebuild(String relative) {
    Kind kind = classify(relative);
    return kind == Kind.USER_TOOL || kind == Kind.DATA;
  }
}
