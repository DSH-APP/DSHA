package com.deepseekharness.app.util;

import java.util.Objects;

/**
 * 一条实体主屏活动事件：只在内存里存在，随任务结束、暂停或无障碍断开清空。
 *
 * <p>只保存摘要所需的最小事实：目标包名/窗口、事件的文字与控件类名、bounds 中心。密码框不保存文字，
 * 由 {@link ScreenActivityPolicy#label} 统一替换为「已输入」。本类不读无障碍、不落盘、不写日志。
 */
public record ScreenActivityEvent(
    long sequence,
    long atMillis,
    Kind kind,
    Source source,
    String packageName,
    String activity,
    long windowId,
    String label,
    String nodeClass,
    int centerX,
    int centerY,
    boolean password,
    boolean sensitive,
    String assistantAction) {

  /** 事件类型；M1 只实现这些，不含会话开始/结束和用户输入轨迹。 */
  public enum Kind {
    TARGET_CHANGED,
    USER_TAP,
    USER_SCROLL,
    CONTENT_CHANGED,
    ASSISTANT_ACTION
  }

  /** 同一类事件按派发窗口区分用户与助手，避免把助手自己的动作记成用户操作。 */
  public enum Source {
    USER,
    ASSISTANT
  }

  public ScreenActivityEvent {
    Objects.requireNonNull(kind, "SCREEN_ACTIVITY_KIND_MISSING");
    Objects.requireNonNull(source, "SCREEN_ACTIVITY_SOURCE_MISSING");
    packageName = packageName == null ? "" : packageName;
    activity = activity == null ? "" : activity;
    label = label == null ? "" : label;
    nodeClass = nodeClass == null ? "" : nodeClass;
    assistantAction = assistantAction == null ? "" : assistantAction;
  }

  /** 由环形缓冲盖章：序号单调递增，时间取记录时刻。 */
  public ScreenActivityEvent stamped(long sequence, long atMillis) {
    return new ScreenActivityEvent(
        sequence,
        atMillis,
        kind,
        source,
        packageName,
        activity,
        windowId,
        label,
        nodeClass,
        centerX,
        centerY,
        password,
        sensitive,
        assistantAction);
  }
}
