package com.deepseekharness.app.util;

/**
 * 侧栏会话行名称后的状态点。纯映射，不读 Android 资源；完整状态文字仍由无障碍描述给出。
 *
 * <p>颜色语义：运行中 = 绿，空闲 = 蓝，需要留意（启动 / 停止 / 结束中 / 待核验 / 待清理 / 失败）= 黄，已退出 =
 * 空心。
 */
public final class TerminalSessionDot {
  public enum Kind {
    RUNNING,
    IDLE,
    PENDING,
    EXITED
  }

  private TerminalSessionDot() {}

  /** PTY 会话：只有运行中 / 待清理 / 已退出三种进程态。 */
  public static Kind forPty(
      boolean closing, boolean unconfirmed, boolean running, boolean cleanupPending) {
    if (closing || unconfirmed) return Kind.PENDING;
    if (running) return Kind.RUNNING;
    return cleanupPending ? Kind.PENDING : Kind.EXITED;
  }

  /** 简易会话：执行中算运行，就绪算空闲，过渡与失败需要留意，未运行算已退出。 */
  public static Kind forSimple(boolean closing, boolean unconfirmed, TerminalSession.State state) {
    if (closing || unconfirmed) return Kind.PENDING;
    if (state == null) return Kind.EXITED;
    return switch (state) {
      case BUSY -> Kind.RUNNING;
      case READY -> Kind.IDLE;
      case STARTING, STOPPING, FAILED -> Kind.PENDING;
      default -> Kind.EXITED;
    };
  }
}
