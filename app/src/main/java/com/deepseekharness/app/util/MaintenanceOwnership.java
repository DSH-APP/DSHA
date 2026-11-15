package com.deepseekharness.app.util;

import java.util.Collection;
import java.util.Set;

/**
 * 区分“本进程正在写的维护记录”与“崩溃 / 中断留下的残留”。只用于主界面入口与提示横幅的路由，
 * 各执行入口（Web 启动、插件、终端、备份）仍直接检查磁盘记录并保持原有拒绝。
 *
 * <p>判定（任一不满足即按残留处理，进入维护页）：
 *
 * <ul>
 *   <li>记录必须全部可读；
 *   <li>记录所属检查项不在本进程首次检查时的基线里（基线中的记录来自上一个进程，必然是残留）；
 *   <li>记录所属检查项有可靠的本进程执行方信号，且该执行方此刻仍在运行。
 * </ul>
 *
 * <p>没有执行方信号的检查项（启动配置修复、旧版事务、首次部署等）一律按残留处理，与原门禁一致。
 */
public final class MaintenanceOwnership {
  private MaintenanceOwnership() {}

  /** 一条未完成的维护记录：检查项 + 记录名。 */
  public record Record(String domain, String operation) {
    public Record {
      domain = domain == null ? "" : domain;
      operation = operation == null ? "" : operation;
    }

    /**
     * 基线按检查项粒度比较：只要该检查项在本进程首次检查时已有记录，之后它的任何记录都按残留处理。
     * 宿主只公开每个检查项的第一条记录，按检查项比较不会把旧记录误认成新记录（偏保守）。
     */
    public String key() {
      return domain;
    }
  }

  /** 有可靠执行方信号的检查项；其余检查项不能被判为“进行中”。 */
  public enum Owner {
    /** 插件安装 / 更新日志：执行方是宿主插件任务队列。 */
    PLUGINS,
    /** 宿主数据、受管运行时、环境重建事务：执行方是数据维护任务。 */
    MAINTENANCE,
    NONE
  }

  public static Owner owner(String domain) {
    if (domain == null) return Owner.NONE;
    return switch (domain) {
      case "plugin-install" -> Owner.PLUGINS;
      case "host-data", "managed-runtime", "environment-rebuild" -> Owner.MAINTENANCE;
      default -> Owner.NONE;
    };
  }

  /**
   * @param current 当前全部未完成记录
   * @param baseline 本进程首次检查时已有记录的检查项（{@link Record#key()}）
   * @param liveOwners 此刻仍在运行的执行方
   * @return true 表示仍需进入维护页；空记录返回 false
   */
  public static boolean blocksEntry(
      Collection<Record> current, Set<String> baseline, Set<Owner> liveOwners) {
    if (current == null || current.isEmpty()) return false;
    if (baseline == null || liveOwners == null) return true;
    for (Record record : current) {
      if (record == null || baseline.contains(record.key())) return true;
      Owner owner = owner(record.domain());
      if (owner == Owner.NONE || !liveOwners.contains(owner)) return true;
    }
    return false;
  }
}
