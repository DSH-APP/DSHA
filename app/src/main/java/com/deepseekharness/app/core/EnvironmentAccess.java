package com.deepseekharness.app.core;

/** 主界面与执行入口共用恢复状态；进入界面不会写入环境就绪标记。 */
public final class EnvironmentAccess {
  private EnvironmentAccess() {}

  public static boolean needsRecovery(HarnessController controller) {
    return !controller.isEnvironmentReady()
        || com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller);
  }

  public static boolean runtimeLatest(HarnessController controller) {
    try {
      var installed = controller.proot().installedRuntimeDescriptor();
      return installed != null && installed.latest(controller.proot().expectedRuntimeDescriptor());
    } catch (java.io.IOException error) {
      return false;
    }
  }

  public static com.deepseekharness.app.util.DshVersionPolicy.Status runtimeVersionStatus(
      HarnessController controller) {
    try {
      return controller.proot().installedVersionStatus();
    } catch (RuntimeException error) {
      return com.deepseekharness.app.util.DshVersionPolicy.Status.UNKNOWN;
    }
  }

  private static long apkUpdatedAt(HarnessController controller) throws java.io.IOException {
    try {
      var context = controller.context();
      long updated =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
      if (updated <= 0) throw new java.io.IOException("APK_UPDATE_TIME_UNAVAILABLE");
      return updated;
    } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) {
      throw new java.io.IOException("APK_UPDATE_TIME_UNAVAILABLE", error);
    }
  }

  /** Same identity is used by both the UI gate and the service startup gate. */
  public static String candidateAttemptKey(HarnessController controller)
      throws java.io.IOException {
    return com.deepseekharness.app.util.EnvironmentIdentity.attemptKey(
        "runtime:" + controller.proot().expectedRuntimeDescriptor().id(), apkUpdatedAt(controller));
  }

  /** A limited-entry choice belongs to the installed APK, not future same-code updates. */
  public static String limitedEntryKey(HarnessController controller) {
    try {
      return com.deepseekharness.app.util.EnvironmentIdentity.attemptKey(
          controller.proot().environmentIdentity(), apkUpdatedAt(controller));
    } catch (java.io.IOException error) {
      return "";
    }
  }

  /** 返回 true 时启动前必须进入维护；新候选只自动尝试一次，健康兼容前代仍可使用。 */
  public static boolean requiresMaintenanceBeforeStart(HarnessController controller) {
    com.deepseekharness.app.util.DshVersionPolicy.Status version = runtimeVersionStatus(controller);
    if (version == com.deepseekharness.app.util.DshVersionPolicy.Status.INSTALLED_NEWER)
      return true;
    if (runtimeLatest(controller)) return false;
    try {
      String candidate = candidateAttemptKey(controller);
      if (com.deepseekharness.app.util.EnvironmentIdentity.unresolvedTask(
          BackupTask.get(controller.context()).snapshot().status)) return true;
      return com.deepseekharness.app.util.EnvironmentIdentity.shouldBlockRuntimeStart(
          false,
          true,
          com.deepseekharness.app.core.MaintenanceCoordinator.isEnvironmentTaskBusy(),
          com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller),
          candidate,
          controller
              .context()
              .getSharedPreferences(
                  "dsha_environment_upgrade", android.content.Context.MODE_PRIVATE)
              .getString("attempted_identity", ""));
    } catch (java.io.IOException error) {
      // 不能认证 APK 自带的运行时描述时，不得由 START_STICKY/看门狗启动旧 Web。
      return com.deepseekharness.app.util.EnvironmentIdentity.shouldBlockRuntimeStart(
          false, false, false, false, "", "");
    }
  }
}
