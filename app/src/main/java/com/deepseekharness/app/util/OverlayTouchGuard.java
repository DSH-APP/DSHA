package com.deepseekharness.app.util;

import java.util.function.LongSupplier;

/**
 * 悬浮助手的防误触规则（纯逻辑，单调时钟）。
 *
 * <ul>
 *   <li>窗口展开、收起或改宽后 {@link #SETTLE_MILLIS} 内忽略点按：同一根手指不会落到刚出现的按钮上。
 *   <li>「停止」需要二次确认：第一次只进入待确认，{@link #CONFIRM_MILLIS} 内再点才生效；超时或点了别的按钮即撤销。
 * </ul>
 */
public final class OverlayTouchGuard {
  public static final long SETTLE_MILLIS = 400;
  public static final long CONFIRM_MILLIS = 3000;

  public enum StopTap {
    IGNORED,
    ARMED,
    CONFIRMED
  }

  private final LongSupplier clock;
  private long settledAt = Long.MIN_VALUE;
  private long stopArmedAt = Long.MIN_VALUE;

  public OverlayTouchGuard(LongSupplier clock) {
    if (clock == null) throw new IllegalArgumentException("OVERLAY_GUARD_CLOCK_MISSING");
    this.clock = clock;
  }

  /** 布局刚变化：开始静置期，并撤销尚未确认的停止。 */
  public synchronized void layoutChanged() {
    settledAt = clock.getAsLong() + SETTLE_MILLIS;
    stopArmedAt = Long.MIN_VALUE;
  }

  /** 普通按钮：静置期内拒绝；接受时撤销待确认的停止。 */
  public synchronized boolean accept() {
    if (settling()) return false;
    stopArmedAt = Long.MIN_VALUE;
    return true;
  }

  public synchronized StopTap stop() {
    if (settling()) return StopTap.IGNORED;
    long now = clock.getAsLong();
    if (armed(now)) {
      stopArmedAt = Long.MIN_VALUE;
      return StopTap.CONFIRMED;
    }
    stopArmedAt = now;
    return StopTap.ARMED;
  }

  public synchronized boolean stopArmed() {
    return armed(clock.getAsLong());
  }

  private boolean armed(long now) {
    return stopArmedAt != Long.MIN_VALUE
        && now >= stopArmedAt
        && now - stopArmedAt <= CONFIRM_MILLIS;
  }

  private boolean settling() {
    return settledAt != Long.MIN_VALUE && clock.getAsLong() < settledAt;
  }
}
