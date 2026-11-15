package com.deepseekharness.app.core;

import com.deepseekharness.app.backup.HostMaintenancePending;
import com.deepseekharness.app.util.MaintenanceExitPolicy;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;

/** 维护记录无法读取：携带域、稳定错误码与出口，消息直接可展示给用户。 */
public final class MaintenanceUnreadable extends IOException {
  public final String domain, code;
  public final MaintenanceExitPolicy.Exit exit;

  private MaintenanceUnreadable(
      String domain, String code, MaintenanceExitPolicy.Exit exit, String message) {
    super(message);
    this.domain = domain;
    this.code = code;
    this.exit = exit;
  }

  static MaintenanceUnreadable explain(
      HostMaintenancePending.Check check, boolean pluginIsolationAvailable) {
    var exit = MaintenanceExitPolicy.exit(check.domain, check.code, pluginIsolationAvailable);
    String message;
    switch (exit) {
      case ISOLATE_PLUGIN_JOURNALS:
        message =
            UiText.format(
                "插件事务日志有记录无法读取（%s），恢复已停止，原件未改动。可在维护页选择「隔离无法读取的插件日志」，把这些记录原样移入隔离区后再恢复。", check.code);
        break;
      case SELECT_DATA_HOME:
        message =
            UiText.format("无法确定使用哪个数据目录（%s），恢复已停止，两份数据都未改动。请在「数据与备份」中选择要使用的数据目录后再恢复。", check.code);
        break;
      default:
        message =
            UiText.format(
                "维护记录无法读取（%s / %s），恢复已停止，原件未改动。这类记录可能处于半完成状态，不提供自动隔离；请导出诊断并联系支持。",
                check.domain, check.code);
        break;
    }
    return new MaintenanceUnreadable(check.domain, check.code, exit, message);
  }
}
