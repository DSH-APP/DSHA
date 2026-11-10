package com.deepseekharness.app.ui;

import android.content.Context;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.EnvironmentAccess;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.MaintenanceCoordinator;

/**
 * 首次引导的只读环境检查。只读取磁盘与任务状态，不写入任何就绪标记、不启动任务；
 * 读取失败一律按“未就绪 / 不拦截”降级，真正的执行门禁仍由维护任务与主界面入口重新核验。
 */
final class OnboardingEnvironment {
  /** 部署预检余量：与 EnvironmentMaintenance 的空间预检一致（解压体积 + 64 MiB）。 */
  static final long SPACE_MARGIN = 64L * 1024 * 1024;

  private OnboardingEnvironment() {}

  /** 已完成首次设置的用户再次进入引导时：环境就绪、无维护进行、无待恢复事务才直接去主界面。 */
  static boolean readyForMain(Context context) {
    try {
      HarnessController controller = HarnessController.get(context);
      return !BackupTask.get(context).maintenanceBusy()
          && !MaintenanceCoordinator.pending(controller)
          && controller.isEnvironmentReady()
          && !EnvironmentAccess.requiresMaintenanceBeforeStart(controller);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /** 部署约需空间；读取失败返回 0（不拦截，冷部署事务自身仍会核验空间）。 */
  static long requiredBytes(Context context) {
    try {
      return Math.addExact(
          HarnessController.get(context).proot().expandedEnvironmentBytes(), SPACE_MARGIN);
    } catch (RuntimeException unavailable) {
      return 0;
    }
  }

  /** 运行环境是否已解压安装（不代表就绪）；读取失败按未安装处理，只用于决定是否做冷部署空间预检。 */
  static boolean installed(Context context) {
    try {
      return HarnessController.get(context).proot().isEnvironmentInstalled();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /** 应用私有目录所在分区的可用空间；读取失败返回 -1（显示“未知”，不拦截）。 */
  static long usableBytes(Context context) {
    try {
      return context.getFilesDir().getUsableSpace();
    } catch (RuntimeException unavailable) {
      return -1;
    }
  }
}
