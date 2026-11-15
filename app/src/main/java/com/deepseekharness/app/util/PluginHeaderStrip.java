package com.deepseekharness.app.util;

/**
 * 插件页头部统一状态条的纯推导：语气、图标、唯一动作与可见性。
 *
 * <p>原来分散在下载源下方的进度条、状态文字、「取消当前操作」与管理页的「检查更新」合并为一条；本类只根据 管理引擎快照里已有的事实决定显示形态，不生成文案、不推算阶段或百分比。
 */
public final class PluginHeaderStrip {
  private PluginHeaderStrip() {}

  public enum Tone {
    ACCENT,
    GOOD,
    WARN,
    BAD,
    MUTED
  }

  public enum Icon {
    /** 忙碌或目录检测中：小号不确定转圈。 */
    SPINNER,
    CHECK,
    ERROR,
    WARN,
    INFO
  }

  /** 状态条右侧唯一动作。 */
  public enum Action {
    NONE,
    /** 队列在工作：取消当前任务；不可取消阶段置灰但仍可见。 */
    CANCEL,
    /** 管理页空闲：检查全部插件更新。 */
    CHECK_UPDATES
  }

  /** 页面需要的事实；字段与 PluginRepository.State / PluginRegistry.Snapshot 一一对应。 */
  public static final class Input {
    public boolean market;
    public boolean busy;
    public boolean cancellable;

    /** 0 无新结果，1 成功，2 失败（PluginRepository.State.RESULT_*）。 */
    public int result;

    public boolean hasMessage;
    public boolean catalogLoading;
    public boolean catalogError;
  }

  public static final class View {
    public final boolean visible;
    public final Tone tone;
    public final Icon icon;
    public final Action action;
    public final boolean actionEnabled;

    /** 管理页空闲时在文案前显示插件计数。 */
    public final boolean showCount;

    /** 有消息但没有动作：尾部显示箭头，提示可点开完整结果。 */
    public final boolean chevron;

    View(
        boolean visible,
        Tone tone,
        Icon icon,
        Action action,
        boolean actionEnabled,
        boolean showCount,
        boolean chevron) {
      this.visible = visible;
      this.tone = tone;
      this.icon = icon;
      this.action = action;
      this.actionEnabled = actionEnabled;
      this.showCount = showCount;
      this.chevron = chevron;
    }
  }

  public static final int RESULT_SUCCESS = 1;
  public static final int RESULT_FAILURE = 2;

  public static View project(Input in) {
    // 市场页没有计数和常驻动作：没有任务也没有消息时整条收起，不留空白。
    boolean visible = !in.market || in.busy || in.hasMessage;
    Tone tone;
    Icon icon;
    if (in.busy) {
      tone = Tone.ACCENT;
      icon = Icon.SPINNER;
    } else if (in.result == RESULT_FAILURE) {
      tone = Tone.BAD;
      icon = Icon.ERROR;
    } else if (in.result == RESULT_SUCCESS) {
      tone = Tone.GOOD;
      icon = Icon.CHECK;
    } else if (in.catalogError) {
      tone = Tone.WARN;
      icon = Icon.WARN;
    } else if (in.catalogLoading) {
      tone = Tone.ACCENT;
      icon = Icon.SPINNER;
    } else {
      tone = Tone.MUTED;
      icon = Icon.INFO;
    }
    Action action = in.busy ? Action.CANCEL : in.market ? Action.NONE : Action.CHECK_UPDATES;
    // busy 只表示队列在工作，不阻止继续提交检查更新；这里只控制取消是否可用。
    boolean enabled = action != Action.CANCEL || in.cancellable;
    boolean showCount = !in.market && !in.busy;
    boolean chevron = in.hasMessage && action == Action.NONE;
    return new View(visible, tone, icon, action, enabled, showCount, chevron);
  }
}
