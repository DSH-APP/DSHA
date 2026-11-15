package com.deepseekharness.app.util;

/**
 * 高危确认的强制等待：按单调时钟计算剩余整秒，界面卡顿或掉帧不会让等待提前结束。
 *
 * <p>纯逻辑，不 import Android API；调用方传入 {@code SystemClock.elapsedRealtime()}。
 */
public final class ConfirmCountdown {
  /** 放宽 / 完全放宽 root 命令拦截前的强制等待秒数。 */
  public static final int ROOT_RELAX_SECONDS = 10;

  private ConfirmCountdown() {}

  /** 剩余等待秒数（向上取整），已到时返回 0；时钟回拨按未开始等待处理，不会提前放行。 */
  public static int remainingSeconds(long startedAtMs, long nowMs, int totalSeconds) {
    long total = Math.max(0, totalSeconds) * 1000L;
    long elapsed = nowMs - startedAtMs;
    if (elapsed < 0) elapsed = 0;
    long left = total - elapsed;
    if (left <= 0) return 0;
    return (int) ((left + 999) / 1000);
  }
}
