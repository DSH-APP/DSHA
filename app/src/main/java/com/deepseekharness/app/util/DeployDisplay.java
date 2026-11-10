package com.deepseekharness.app.util;

/**
 * 部署胶囊的显示判定（纯逻辑）：在 {@link DeployStages#phase} 之上补充“只显示环境维护任务”和
 * “不重放历史成功”两条规则。只决定显示，不决定是否开始、重试任务，也不写任何就绪标记。
 */
public final class DeployDisplay {
  private DeployDisplay() {}

  /**
   * @param snapshotBusy 快照本身处于运行 / 等待确认（应用级任务正在执行）
   * @param pending 磁盘上有未完成的维护事务
   * @param ready 环境就绪检查结果（未知时按未就绪传入）
   * @param observed 本进程内观察到该任务运行过（只有这种成功才显示为“已完成”）
   */
  public static DeployStages.Phase phase(
      BackupTaskState.Status status,
      String kind,
      boolean snapshotBusy,
      boolean pending,
      boolean ready,
      boolean observed) {
    if (snapshotBusy)
      return DeployStages.environmentKind(kind)
          ? DeployStages.Phase.RUNNING
          : DeployStages.Phase.IDLE;
    if (pending) return DeployStages.Phase.PENDING;
    if (!DeployStages.environmentKind(kind)) return DeployStages.Phase.IDLE;
    DeployStages.Phase phase = DeployStages.phase(status, false, false, ready);
    switch (phase) {
      case SUCCEEDED:
        // 历史成功（例如上次进程留下的记录）不再弹出绿色胶囊。
        return observed ? DeployStages.Phase.SUCCEEDED : DeployStages.Phase.IDLE;
      case FAILED:
        // 历史失败只在环境仍未就绪、或中断 / 取消（启动前仍须维护）时提示；本进程内的失败一直显示到用户重试。
        return observed || !ready || EnvironmentIdentity.unresolvedTask(status)
            ? DeployStages.Phase.FAILED
            : DeployStages.Phase.IDLE;
      default:
        return phase;
    }
  }

  /** 已成功完成的任务在主界面收起的延迟。 */
  public static final long COLLAPSE_AFTER_MILLIS = 5_000L;

  /** 成功胶囊是否已到收起时间；successAt 为首次显示成功的时刻（≤0 表示未知，不收起）。 */
  public static boolean collapse(DeployStages.Phase phase, long successAt, long now) {
    return phase == DeployStages.Phase.SUCCEEDED
        && successAt > 0
        && now - successAt >= COLLAPSE_AFTER_MILLIS;
  }

  /** 渲染去重键：内容不变时不重复回调界面。 */
  public static String key(DeployStages.View view) {
    if (view == null) return "";
    StringBuilder key =
        new StringBuilder()
            .append(view.taskId)
            .append('|')
            .append(view.mode)
            .append('|')
            .append(view.phase)
            .append('|')
            .append(view.current)
            .append('|');
    for (DeployStages.Step step : view.steps) key.append(step.ordinal());
    return key.append('|')
        .append(view.detail)
        .append('|')
        .append(view.lastStage)
        .append('|')
        .append(UiText.language())
        .toString();
  }
}
