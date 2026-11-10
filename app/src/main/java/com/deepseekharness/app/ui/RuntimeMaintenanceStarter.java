package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.EnvironmentAccess;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BackupTaskKinds;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.EnvironmentIdentity;
import com.deepseekharness.app.util.UiText;

/**
 * 运行环境维护的唯一启动入口：首次部署、覆盖更新的自动开始与失败后的手动重试共用同一套门禁。
 *
 * <p>自动开始沿用 ExtractActivity 的 {@code attempted_identity} 记录与 {@link
 * EnvironmentIdentity#shouldAutoStart}：同一候选只自动尝试一次，先记录再启动，旋转、失败与进程重建都不会
 * 重复覆盖环境。手动重试必须经用户在 {@link DshaDialogBuilder} 安全确认后执行。这里不写任何就绪标记。
 */
final class RuntimeMaintenanceStarter {
  /** 历史 SharedPreferences 名称与键，不得改名。 */
  static final String ATTEMPTS = "dsha_environment_upgrade";

  static final String ATTEMPTED_IDENTITY = "attempted_identity";

  /** 自动开始的结果：DECLINED 表示已记下这次尝试，但应用级任务没有接受（已有任务或门禁变化）。 */
  enum Auto {
    NOT_ELIGIBLE,
    STARTED,
    DECLINED
  }

  /** 手动重试会执行的维护动作；按钮文案与确认对话框共用同一判定。 */
  enum Retry {
    RECOVER,
    UPDATE,
    DEPLOY,
    REBUILD
  }

  private RuntimeMaintenanceStarter() {}

  /**
   * 首次部署：仅在环境未就绪、无维护进行、无待恢复事务时启动应用级任务；成功返回 true。 不重复启动；同一候选只尝试一次。
   */
  static boolean startInitialDeploy(Context context) {
    HarnessController controller = HarnessController.get(context);
    BackupTask task = BackupTask.get(context);
    com.deepseekharness.app.util.DshVersionPolicy.Status version =
        EnvironmentAccess.runtimeVersionStatus(controller);
    if (version == com.deepseekharness.app.util.DshVersionPolicy.Status.INSTALLED_NEWER)
      return false;
    if (task.maintenanceBusy() || task.busy() || task.pendingMaintenance()) return false;
    if (controller.isEnvironmentReady()) return false;
    return attemptOnce(context, controller, task, false) == Auto.STARTED;
  }

  /** 覆盖更新的自动开始：满足 shouldAutoStart 时记一次 attempted_identity 再启动 updateEnvironment。 */
  static boolean autoStartUpdateIfEligible(Context context) {
    return autoStart(context) == Auto.STARTED;
  }

  /** 与 {@link #autoStartUpdateIfEligible} 相同，额外区分“已记录尝试但任务未被接受”。 */
  static Auto autoStart(Context context) {
    HarnessController controller = HarnessController.get(context);
    BackupTask task = BackupTask.get(context);
    // 新版写出的数据不能由旧候选验证；只提示用户并停在维护页，禁止自动原位修复。
    com.deepseekharness.app.util.DshVersionPolicy.Status version =
        EnvironmentAccess.runtimeVersionStatus(controller);
    if (version == com.deepseekharness.app.util.DshVersionPolicy.Status.INSTALLED_NEWER)
      return Auto.NOT_ELIGIBLE;
    // 有未关闭的试运行现场时自动更新必然以 TRIAL_RECOVERY_REQUIRED 失败，先由用户恢复。
    if (task.maintenanceBusy() || needsRecovery(context)) return Auto.NOT_ELIGIBLE;
    if (controller.isEnvironmentReady()
        && !EnvironmentAccess.requiresMaintenanceBeforeStart(controller)) return Auto.NOT_ELIGIBLE;
    return attemptOnce(context, controller, task, EnvironmentAccess.runtimeLatest(controller));
  }

  private static Auto attemptOnce(
      Context context, HarnessController controller, BackupTask task, boolean latest) {
    com.deepseekharness.app.util.DshVersionPolicy.Status version =
        EnvironmentAccess.runtimeVersionStatus(controller);
    if (version == com.deepseekharness.app.util.DshVersionPolicy.Status.INSTALLED_NEWER)
      return Auto.NOT_ELIGIBLE;
    SharedPreferences attempts =
        context.getApplicationContext().getSharedPreferences(ATTEMPTS, Context.MODE_PRIVATE);
    String attemptKey = "";
    try {
      attemptKey = EnvironmentAccess.candidateAttemptKey(controller);
    } catch (java.io.IOException invalid) {
      // 无法认证包内运行时描述时 attemptKey 为空，shouldAutoStart 会拒绝自动开始。
    }
    boolean ready = controller.isEnvironmentReady();
    if (!EnvironmentIdentity.shouldAutoStart(
        ready && latest,
        task.busy(),
        task.pendingMaintenance(),
        attemptKey,
        attempts.getString(ATTEMPTED_IDENTITY, ""),
        task.snapshot().status,
        ready,
        controller.hasUnresolvedWebSession())) return Auto.NOT_ELIGIBLE;
    // 先记一次尝试再启动应用级任务；旋转、失败和进程重建都不会重复覆盖旧环境。
    if (!attempts.edit().putString(ATTEMPTED_IDENTITY, attemptKey).commit())
      return Auto.NOT_ELIGIBLE;
    boolean installed = controller.proot().isEnvironmentInstalled();
    if (!task.updateEnvironment()) return Auto.DECLINED;
    DeployMonitor.noteStarted(context, task.snapshot().id, installed);
    return Auto.STARTED;
  }

  /** 首次安装（环境未安装）：重试走同一任务，updateEnvironment 会落到 rebuild → initializeFresh 冷部署。 */
  static boolean firstInstall(Context context) {
    try {
      return !HarnessController.get(context).proot().isEnvironmentInstalled();
    } catch (RuntimeException unknown) {
      return false;
    }
  }

  /**
   * 只读：files/runtime-trials 下是否有未确认关闭的隔离试运行现场（无 closed 标记）。
   *
   * <p>覆盖更新的试运行被清后台、重装或进程回收打断时会留下这种现场；新的试运行会以
   * TRIAL_RECOVERY_REQUIRED 拒绝开始，而它不在宿主维护事务门禁里。只有“恢复中断维护”
   * （EnvironmentMaintenance.recover → RuntimeTrial.recoverPending）会核实进程退出后关闭它，
   * 所以重试必须改走恢复，不能再次发起更新。这里不删除、不修改任何文件。
   */
  static boolean pendingTrial(Context context) {
    // 维护页每秒重绘会调用，但这里不做跨调用缓存：应急 DSH 的外部恢复完成后，页面下一次
    // 重绘就必须反映真实的 trial 记录状态，不能先沿用恢复前的旧值再等缓存过期。
    // 目录列举 + 少量 exists 的开销远小于让用户误以为仍要再恢复一次。
    return scanPendingTrial(context);
  }

  private static boolean scanPendingTrial(Context context) {
    try {
      java.io.File home =
          new java.io.File(context.getFilesDir().getCanonicalFile(), "runtime-trials");
      String[] entries = home.list();
      if (entries == null) return false;
      for (String id : entries) {
        if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)) continue;
        java.io.File operation = new java.io.File(home, id);
        if (operation.isDirectory() && !new java.io.File(operation, "closed").exists()) return true;
      }
      return false;
    } catch (java.io.IOException | RuntimeException unreadable) {
      return false;
    }
  }

  /** 需要先“恢复中断维护”：宿主维护事务，或未关闭的隔离试运行现场。 */
  static boolean needsRecovery(Context context) {
    return BackupTask.get(context).pendingMaintenance() || pendingTrial(context);
  }

  /** 与 ExtractActivity 原 retry 分支一致：待恢复优先，其次原位更新，首次安装为重试部署，否则保护数据并重建。 */
  static Retry retryKind(Context context, boolean rebuildRequested) {
    HarnessController controller = HarnessController.get(context);
    if (needsRecovery(context)) return Retry.RECOVER;
    if (!rebuildRequested && controller.proot().canUpdateManagedRuntime()) return Retry.UPDATE;
    if (firstInstall(context)) return Retry.DEPLOY;
    return Retry.REBUILD;
  }

  /** 失败后重试；任务种类为重建时保持重建语义。 */
  static void confirmRetry(AppCompatActivity activity, Runnable afterStart) {
    confirmRetry(
        activity,
        afterStart,
        BackupTaskKinds.is(BackupTaskKinds.REBUILD, BackupTask.get(activity).snapshot().kind));
  }

  static void confirmRetry(
      AppCompatActivity activity, Runnable afterStart, boolean rebuildRequested) {
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
    Retry kind = retryKind(activity, rebuildRequested);
    if (kind == Retry.RECOVER) {
      offerRecoveryOrIsolation(activity, afterStart, rebuildRequested);
      return;
    }
    showRetryDialog(activity, afterStart, rebuildRequested, kind);
  }

  /**
   * 恢复前在后台只读计算插件日志隔离计划：有坏记录时先请用户确认隔离（摘要绑定到执行），
   * 否则走原有恢复确认。计划读不出时按原恢复流程，由恢复入口给出精确诊断。
   */
  private static void offerRecoveryOrIsolation(
      AppCompatActivity activity, Runnable afterStart, boolean rebuildRequested) {
    java.io.File files = activity.getFilesDir();
    new Thread(
            () -> {
              var offer = com.deepseekharness.app.core.PluginJournalIsolation.offerOrNull(files);
              activity.runOnUiThread(
                  () -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (offer == null)
                      showRetryDialog(activity, afterStart, rebuildRequested, Retry.RECOVER);
                    else confirmIsolation(activity, afterStart, offer);
                  });
            },
            "plugin-journal-isolation-plan")
        .start();
  }

  static String isolationSummary(
      java.util.List<com.deepseekharness.app.backup.PluginInstallJournals.Problem> problems) {
    StringBuilder rows = new StringBuilder();
    int shown = Math.min(problems.size(), 8);
    for (int i = 0; i < shown; i++)
      rows.append("\n• ")
          .append(problems.get(i).path)
          .append(" (")
          .append(problems.get(i).reason)
          .append(")");
    if (problems.size() > shown)
      rows.append("\n").append(UiText.format("另有 %s 条", problems.size() - shown));
    return UiText.format(
            "检测到 %s 条无法读取的插件事务日志，恢复中断维护会因此一直失败。将把下列记录原样移入 plugin-install-quarantine 隔离区并留下清单，不删除、不重放；会话与配置不受影响，相关插件之后可能需要重新安装。",
            problems.size())
        + rows;
  }

  private static void confirmIsolation(
      AppCompatActivity activity,
      Runnable afterStart,
      com.deepseekharness.app.core.PluginJournalIsolation.Offer offer) {
    BackupTask task = BackupTask.get(activity);
    HarnessController controller = HarnessController.get(activity);
    new DshaDialogBuilder(activity, true)
        .setTitle(UiText.text("隔离无法读取的插件日志？"))
        .setMessage(isolationSummary(offer.problems))
        .setPositiveButton(
            UiText.text("隔离并保留原件"),
            (dialog, which) -> {
              boolean installed = controller.proot().isEnvironmentInstalled();
              if (task.isolatePluginJournals(offer.digest)) {
                DeployMonitor.noteStarted(activity, task.snapshot().id, installed);
                if (afterStart != null) afterStart.run();
              } else if (!activity.isFinishing() && !activity.isDestroyed()) {
                android.widget.Toast.makeText(
                        activity, UiText.text("隔离未能开始：已有任务正在进行。"), android.widget.Toast.LENGTH_LONG)
                    .show();
              }
            })
        .setNegativeButton(UiText.text("取消"), null)
        .show();
  }

  private static void showRetryDialog(
      AppCompatActivity activity, Runnable afterStart, boolean rebuildRequested, Retry kind) {
    BackupTask task = BackupTask.get(activity);
    HarnessController controller = HarnessController.get(activity);
    String title, message;
    switch (kind) {
      case RECOVER:
        title = UiText.text("恢复原环境？");
        message = UiText.text("先停止 Web，再回切旧环境，保留所有安全副本。");
        break;
      case UPDATE:
        title = UiText.text("更新运行时？");
        message = UiText.text("会停止 Web 和终端任务，再更新 dsh 和内置插件，个人目录、会话和配置保持原位。验证失败回切原运行时。");
        break;
      case DEPLOY:
        title = UiText.choose("重试部署？", "Retry deployment?");
        message =
            UiText.choose(
                "将在应用私有目录重新部署内置 Ubuntu 与运行组件，并完成启动检查与隔离试运行；已有数据按维护规则保留。",
                "The bundled Ubuntu and runtime components will be deployed again to the app's private storage, followed by startup checks and an isolated trial. Existing data is retained under the maintenance rules.");
        break;
      default:
        title = UiText.text("保护个人数据并重建？");
        message =
            UiText.text(
                "将停止 Web 和终端，只备份对话、附件、配置、插件与个人项目，不打包 Ubuntu / Node 系统。数据保护失败不切换环境，重建失败保留原环境。");
        break;
    }
    new DshaDialogBuilder(activity, true)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(
            UiText.text("继续"),
            (dialog, which) -> {
              boolean installed = controller.proot().isEnvironmentInstalled();
              // 与原维护页相同：待恢复走恢复；明确重建保持重建；其余走 updateEnvironment
              // （不能原位更新时由 EnvironmentMaintenance.update 落到 rebuild / 首次冷部署）。
              boolean started =
                  kind == Retry.RECOVER
                      ? task.recoverMaintenance()
                      : rebuildRequested ? task.rebuild() : task.updateEnvironment(true);
              if (started) {
                DeployMonitor.noteStarted(activity, task.snapshot().id, installed);
                if (afterStart != null) afterStart.run();
              } else if (!activity.isFinishing() && !activity.isDestroyed()) {
                android.widget.Toast.makeText(
                        activity,
                        UiText.choose(
                            "维护未能开始：已有任务正在进行，或需要先恢复中断维护。",
                            "Maintenance did not start: another task is running, or interrupted maintenance must be recovered first."),
                        android.widget.Toast.LENGTH_LONG)
                    .show();
              }
            })
        .setNegativeButton(UiText.text("取消"), null)
        .show();
  }

  /** 重试按钮文案；与确认对话框使用同一判定。retry 为 true 表示上次任务未完成。 */
  static String retryLabel(Retry kind, boolean retry) {
    switch (kind) {
      case RECOVER:
        return UiText.text("恢复中断维护");
      case UPDATE:
        return retry ? UiText.choose("重试更新运行时", "Retry runtime update") : UiText.text("更新运行时");
      case DEPLOY:
        return retry
            ? UiText.choose("重试部署", "Retry deployment")
            : UiText.choose("开始部署", "Start deployment");
      default:
        return UiText.text("保护数据并重建");
    }
  }
}
