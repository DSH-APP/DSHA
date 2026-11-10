package com.deepseekharness.app.ui;

import android.content.Context;
import com.deepseekharness.app.backup.HostMaintenancePending;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.MaintenanceOwnership;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 主界面入口与环境提示横幅专用的维护判定：未完成记录是否需要整屏进入维护页。
 *
 * <p>与 {@code MaintenanceCoordinator.pending} 的区别：本进程启动后才出现、且执行方此刻仍在运行的记录
 * 视为“进行中”，用户留在主界面，后台进度由横幅与各业务页表达；崩溃 / 中断残留仍进入维护页。
 * Web 启动、插件、终端、备份等执行入口不使用此判定，继续按磁盘记录拒绝。
 *
 * <p>只读宿主已公开的检查结果，不改动受管运行时契约里的源文件。有界 guest 屏障、任何读失败及
 * 无执行方信号的检查项一律按需要维护处理。
 */
final class MainEntryMaintenance {
  /** 本进程首次检查时已有记录的检查项；进程回收后重新建立。 */
  private static Set<String> baseline;

  private MainEntryMaintenance() {}

  /** 在后台线程调用：会读磁盘。 */
  static boolean blocksEntry(HarnessController controller) {
    try {
      File files = controller.proot().getRootfsDir().getParentFile().getParentFile();
      if (com.deepseekharness.app.runtime.BoundedGuestSessions.firstPending(files) != null)
        return true;
      List<MaintenanceOwnership.Record> records = new ArrayList<>();
      for (HostMaintenancePending.Check check : HostMaintenancePending.inspectAll(files)) {
        if (!check.blocked) continue;
        if (check.unreadable()) return true;
        records.add(new MaintenanceOwnership.Record(check.domain, check.operation));
      }
      Set<String> known;
      synchronized (MainEntryMaintenance.class) {
        if (baseline == null) {
          Set<String> first = new HashSet<>();
          for (MaintenanceOwnership.Record record : records) first.add(record.key());
          baseline = Collections.unmodifiableSet(first);
        }
        known = baseline;
      }
      if (records.isEmpty()) return false;
      return MaintenanceOwnership.blocksEntry(records, known, liveOwners(controller.context()));
    } catch (Exception | LinkageError unavailable) {
      return true;
    }
  }

  /** 此刻仍在运行的执行方：插件任务队列、数据维护任务（含原生备份恢复）。 */
  private static Set<MaintenanceOwnership.Owner> liveOwners(Context context) {
    Set<MaintenanceOwnership.Owner> owners = EnumSet.noneOf(MaintenanceOwnership.Owner.class);
    if (com.deepseekharness.app.core.PluginRepository.get(context).isBusy())
      owners.add(MaintenanceOwnership.Owner.PLUGINS);
    if (com.deepseekharness.app.core.BackupTask.get(context).maintenanceBusy()
        || com.deepseekharness.app.backup.NativeBackupJobs.get(context).state().busy)
      owners.add(MaintenanceOwnership.Owner.MAINTENANCE);
    return owners;
  }
}
