package com.deepseekharness.app.util;

/**
 * 维护记录「无法读取」时的出口选择（纯逻辑）。每个出口都不依赖出问题的那个域探测成功：
 *
 * <ul>
 *   <li>{@link Exit#ISOLATE_PLUGIN_JOURNALS}：仅插件日志域；原生确认后把坏记录原样移入隔离区。
 *   <li>{@link Exit#SELECT_DATA_HOME}：数据根无法确定（DATA_LOCATION_*）；选择数据目录本身即修复。
 *   <li>{@link Exit#DIAGNOSTICS}：宿主数据、运行时、环境重建等可能半应用的事务；只给精确诊断，
 *       不跳过其回滚，避免个人数据不一致。
 * </ul>
 */
public final class MaintenanceExitPolicy {
  public enum Exit {
    ISOLATE_PLUGIN_JOURNALS,
    SELECT_DATA_HOME,
    DIAGNOSTICS
  }

  public static final String PLUGIN_DOMAIN = "plugin-install";

  private MaintenanceExitPolicy() {}

  public static boolean dataLocation(String code) {
    return code != null && code.startsWith("DATA_LOCATION_");
  }

  /** pluginIsolationAvailable：插件日志隔离计划非空（数据根可确定且确有坏记录）。 */
  public static Exit exit(String domain, String code, boolean pluginIsolationAvailable) {
    if (dataLocation(code)) return Exit.SELECT_DATA_HOME;
    if (PLUGIN_DOMAIN.equals(domain) && pluginIsolationAvailable)
      return Exit.ISOLATE_PLUGIN_JOURNALS;
    return Exit.DIAGNOSTICS;
  }
}
