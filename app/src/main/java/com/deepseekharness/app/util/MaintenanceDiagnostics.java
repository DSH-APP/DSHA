package com.deepseekharness.app.util;

import java.util.Set;

/**
 * 环境维护的可诊断策略：把稳定错误码归类为可行动原因，并判断保护阶段是否长时间没有字节进度。
 *
 * <p>只做纯判定，不拼接宿主绝对路径或异常原文；界面按 {@link Failure} 与逻辑根名生成文案。原因码保持
 * 与 {@code BackupErrorCode} 的上限一致，未知输入归 {@link Failure#OTHER}。
 */
public final class MaintenanceDiagnostics {
  private MaintenanceDiagnostics() {}

  /** 连续无进度达到该阈值才提示“可能卡住”，避免慢盘/大文件误报。 */
  public static final long STALL_MS = 120_000L;

  public enum Failure {
    NONE,
    NO_SPACE,
    ROOT_UNREADABLE,
    SOURCE_CHANGED,
    SERVICE_UNAVAILABLE,
    ENTRY_LIMIT,
    OTHER
  }

  private static final Set<String> ROOT_UNREADABLE =
      Set.of(
          "PERMISSION_DENIED",
          "MAINTENANCE_ROOT_TYPE",
          "MAINTENANCE_TARGET_TYPE",
          "MAINTENANCE_SOURCE_MISSING",
          "SOURCE_UNREADABLE",
          "MISSING_SOURCE",
          "UNREADABLE_SOURCE",
          "PARENT_LINK",
          "PARENT_LINK_OR_MISSING");

  private static final Set<String> SOURCE_CHANGED =
      Set.of(
          "DATA_LOCATION_CHANGED",
          "WORKSPACE_CHANGED",
          "WORKSPACE_REGISTRY_CHANGED",
          "SOURCE_CHANGED",
          "TARGET_CHANGED");

  public static Failure classify(String code) {
    String value = code == null ? "" : code;
    if ("NO_SPACE".equals(value)) return Failure.NO_SPACE;
    if (ROOT_UNREADABLE.contains(value)) return Failure.ROOT_UNREADABLE;
    if (SOURCE_CHANGED.contains(value)) return Failure.SOURCE_CHANGED;
    if ("FOREGROUND_SERVICE_UNAVAILABLE".equals(value) || "SERVICE_UNAVAILABLE".equals(value))
      return Failure.SERVICE_UNAVAILABLE;
    if ("ENTRY_LIMIT".equals(value) || "WORKSPACE_LIMIT".equals(value)) return Failure.ENTRY_LIMIT;
    return Failure.OTHER;
  }

  public static boolean stalled(long idleMs) {
    return idleMs >= STALL_MS;
  }

  /** 逻辑根名只保留路径安全字符；非法输入返回空串，界面不显示未知路径。 */
  public static String target(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    // 逻辑根是相对名（如 root/.dsh）；宿主绝对路径一律不接受，避免把设备路径带进界面。
    if (trimmed.isEmpty() || trimmed.length() > 120 || trimmed.startsWith("/")) return "";
    for (int index = 0; index < trimmed.length(); index++) {
      char c = trimmed.charAt(index);
      boolean safe =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '/'
              || c == '.'
              || c == '_'
              || c == '-';
      if (!safe) return "";
    }
    return trimmed;
  }
}
