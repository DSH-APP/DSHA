package com.deepseekharness.app.util;

/**
 * 导出被立即拒绝时的原因与下一步文案。
 *
 * <p>只接受固定的原因名；未知值按通用失败处理。救援导出（只读）不要求维护锁，因此「维护待恢复」只对
 * 普通导出给出恢复入口提示。
 */
public final class BackupExportGuidance {
  private BackupExportGuidance() {}

  public enum Kind {
    NONE,
    BUSY,
    WORKERS_CLOSED,
    FACTORY_RESET_PENDING,
    MAINTENANCE_PENDING,
    INVALID_SELECTION,
    PASSWORD_INVALID,
    RETAINED_KEY,
    SOURCE_COPY,
    PRIVATE_STORAGE,
    WORKER_UNAVAILABLE,
    UNKNOWN
  }

  public static Kind classify(String reason) {
    if (reason == null) return Kind.UNKNOWN;
    try {
      return Kind.valueOf(reason);
    } catch (IllegalArgumentException unavailable) {
      return Kind.UNKNOWN;
    }
  }

  /** 返回一句可行动说明；不包含路径、凭据或异常原文。 */
  public static String message(String reason, boolean rescue) {
    Kind kind = classify(reason);
    return switch (kind) {
      case NONE -> UiText.text("导出已开始。");
      case BUSY ->
          UiText.choose(
              "已有数据任务正在运行，请等待当前任务结束后重试。",
              "Another data task is running. Wait for it to finish and retry.");
      case WORKERS_CLOSED ->
          UiText.choose(
              "数据作业已关闭，请重启应用后再导出。", "Data jobs are closed. Restart the app and export again.");
      case FACTORY_RESET_PENDING ->
          UiText.choose(
              "正在等待格式化数据，暂时不能导出。请先完成或取消格式化。",
              "Data reset is pending, so export is unavailable. Finish or cancel the reset first.");
      case MAINTENANCE_PENDING ->
          rescue
              ? UiText.choose(
                  "维护记录尚待恢复；救援导出仍可继续。",
                  "Maintenance records still need recovery; rescue export can continue.")
              : UiText.choose(
                  "上次环境维护未完成：请先到安装与修复页恢复，再导出备份。",
                  "The last environment maintenance did not finish. Recover it in Installation and repair before exporting.");
      case INVALID_SELECTION ->
          UiText.choose(
              "导出范围或项目参数无效，请重新选择后再试。",
              "The export scope or project selection is invalid. Select again and retry.");
      case PASSWORD_INVALID ->
          UiText.choose(
              "内部自动副本的密码长度不符合要求；用户导出不需要密码，请直接重试。",
              "The internal automatic-copy password length is invalid. User exports need no password; retry directly.");
      case RETAINED_KEY ->
          UiText.choose(
              "该私有副本只用于内部核对，不能直接导出。请从副本列表选择「重新导出」。",
              "This private copy is for internal verification only. Choose \"Export again\" from the copy list.");
      case SOURCE_COPY ->
          UiText.choose(
              "所选项目目录的访问凭据不可用，请重新选择目录。",
              "The selected project folder's permission is unavailable. Select the folder again.");
      case PRIVATE_STORAGE ->
          UiText.choose(
              "无法在私有目录创建导出作业：请检查可用空间、前台服务权限后重试。",
              "The private export job could not be created. Check free space and foreground-service permission, then retry.");
      case WORKER_UNAVAILABLE ->
          UiText.choose(
              "无法启动数据作业线程，请稍后重试或重启应用。",
              "The data job thread could not start. Retry shortly or restart the app.");
      case UNKNOWN ->
          UiText.choose("导出未开始。请查看状态后重试。", "Export did not start. Review the state and retry.");
    };
  }
}
