package com.deepseekharness.app.util;

/**
 * 主界面顶部环境提示横幅的状态判定。优先级与原横幅一致：顶栏部署胶囊显示时由胶囊接管；
 * 其余按 维护进行中 &gt; 受限模式 &gt; 启动未完成 取一种。只决定显示哪一种，去向仍由主界面按真实状态路由。
 */
public final class RecoveryBannerState {
  private RecoveryBannerState() {}

  public enum Kind {
    NONE,
    /** 环境维护 / 备份恢复等任务正在执行。 */
    BUSY,
    /** 恢复备份已预检，等待用户确认。 */
    AWAITING_CONFIRMATION,
    /** 用户从维护页进入受限主界面，环境仍需恢复。 */
    LIMITED,
    /** 上次启动未完成，有恢复选项可看。 */
    STARTUP
  }

  /** 视觉语气：BUSY 用强调色并转圈，LIMITED 用警示色，STARTUP 用错误色。 */
  public enum Tone {
    ACCENT,
    WARN,
    BAD
  }

  public static Kind decide(
      boolean capsuleShown,
      boolean busy,
      boolean awaitingConfirmation,
      boolean limited,
      boolean startupRecovery) {
    if (capsuleShown) return Kind.NONE;
    if (busy) return awaitingConfirmation ? Kind.AWAITING_CONFIRMATION : Kind.BUSY;
    if (limited) return Kind.LIMITED;
    if (startupRecovery) return Kind.STARTUP;
    return Kind.NONE;
  }

  public static Tone tone(Kind kind) {
    return switch (kind) {
      case LIMITED, AWAITING_CONFIRMATION -> Tone.WARN;
      case STARTUP -> Tone.BAD;
      default -> Tone.ACCENT;
    };
  }

  /** 是否显示进行中转圈（而不是状态图标）。 */
  public static boolean spinning(Kind kind) {
    return kind == Kind.BUSY;
  }
}
