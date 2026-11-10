package com.deepseekharness.app.util;

/**
 * 确定性进度的状态机（E1）。
 *
 * <p>真实百分比只增不减，终态一经写入不再改变；取消只能在进行中发起，取消后不再接受新的
 * 百分比。是否 100% 只由 {@link #complete()} 决定——渲染层不按“看起来快满了”自己补进度。
 */
public final class ProgressState {
  public enum Phase {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
  }

  private Phase phase = Phase.IDLE;
  private int percent = -1;
  private boolean cancelRequested;

  public Phase phase() {
    return phase;
  }

  public boolean running() {
    return phase == Phase.RUNNING;
  }

  /** 终态：完成 / 失败 / 取消，之后不再有动画，也不再接受进度。 */
  public boolean terminal() {
    return phase == Phase.COMPLETED || phase == Phase.FAILED || phase == Phase.CANCELLED;
  }

  /** 当前真实百分比；尚未开始返回 -1。 */
  public int percent() {
    return percent;
  }

  /** 已请求取消、等待后端收尾时为 true。 */
  public boolean cancelRequested() {
    return cancelRequested;
  }

  public boolean start() {
    if (phase != Phase.IDLE) return false;
    phase = Phase.RUNNING;
    percent = 0;
    cancelRequested = false;
    return true;
  }

  /**
   * 上报真实百分比；100 只留给 {@link #complete()}，所以运行中封顶 99。
   *
   * @return 百分比是否真的变了（未变时调用方不必重绘）
   */
  public boolean report(int value) {
    if (phase != Phase.RUNNING || cancelRequested) return false;
    int next = Math.max(percent, Math.max(0, Math.min(99, value)));
    if (next == percent) return false;
    percent = next;
    return true;
  }

  /** 请求取消；只有一次，重复请求返回 false。 */
  public boolean requestCancel() {
    if (phase != Phase.RUNNING || cancelRequested) return false;
    cancelRequested = true;
    return true;
  }

  public boolean complete() {
    if (phase != Phase.RUNNING) return false;
    phase = Phase.COMPLETED;
    percent = 100;
    return true;
  }

  public boolean fail() {
    if (phase != Phase.RUNNING) return false;
    phase = Phase.FAILED;
    return true;
  }

  public boolean cancelled() {
    if (phase != Phase.RUNNING) return false;
    phase = Phase.CANCELLED;
    return true;
  }

  public void reset() {
    phase = Phase.IDLE;
    percent = -1;
    cancelRequested = false;
  }
}
