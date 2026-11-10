package com.deepseekharness.app.util;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 无障碍事件脉冲：动作等待期间只记录最近少量事件的时间与窗口身份，供 settle 判定使用；不是读屏缓存。
 *
 * <p>没有等待者时 {@link #record} 直接返回，系统事件流不会因此被复制或缓存。等待使用监视器唤醒，有界且可被任务
 * 撤销打断；判断规则在 {@link ScreenSettlePolicy} 内，本类只负责记录与等待。
 */
public final class ScreenEventPulse {
  private static final int MAX_EVENTS = 64;

  private final LongSupplier clock;
  private final long[] times = new long[MAX_EVENTS];
  private final long[] windows = new long[MAX_EVENTS];
  private final String[] packages = new String[MAX_EVENTS];
  private int size;
  private int next;
  private int watchers;
  private volatile boolean watching;

  public ScreenEventPulse(LongSupplier clock) {
    if (clock == null) throw new IllegalArgumentException("SCREEN_EVENT_CLOCK_MISSING");
    this.clock = clock;
  }

  /** 开始一次等待；事件只在这段时间内记录。成对调用 {@link #endWatch()}，异常路径也不例外。 */
  public synchronized void beginWatch() {
    watchers++;
    watching = true;
  }

  public synchronized void endWatch() {
    if (watchers > 0) watchers--;
    watching = watchers > 0;
    if (!watching) clearEvents();
  }

  public boolean watching() {
    return watching;
  }

  /** 无障碍回调线程调用；只写最近 {@value #MAX_EVENTS} 条，覆盖最旧的一条。 */
  public synchronized void record(long windowId, String packageName) {
    if (!watching) return;
    long now = clock.getAsLong();
    times[next] = now;
    windows[next] = windowId;
    packages[next] = packageName == null ? "" : packageName;
    next = (next + 1) % MAX_EVENTS;
    if (size < MAX_EVENTS) size++;
    notifyAll();
  }

  /**
   * 等到界面稳定、总上限到点、或等待被撤销。事件按窗口号或包名匹配目标窗口（新弹出的对话框常换窗口号但仍在同一包）。
   *
   * @param deadlineElapsed 调用方允许的最晚结束时刻（单调时钟）；到了就按超时返回，不超出请求预算
   * @param keepWaiting 返回 false 时立即结束（任务暂停、取消、身份变化），不把等待当成观察授权
   */
  public ScreenSettlePolicy.Outcome await(
      long startedAt,
      String packageName,
      long windowId,
      long deadlineElapsed,
      ScreenSettlePolicy.Config config,
      BooleanSupplier keepWaiting) {
    if (config == null) throw new IllegalArgumentException("SCREEN_SETTLE_CONFIG_MISSING");
    if (keepWaiting == null) throw new IllegalArgumentException("SCREEN_SETTLE_AUTHORITY_MISSING");
    if (startedAt < 0) throw new IllegalArgumentException("SCREEN_SETTLE_START_MISSING");
    synchronized (this) {
      while (true) {
        long now = clock.getAsLong();
        Snapshot snapshot = scan(startedAt, packageName, windowId);
        ScreenSettlePolicy.Decision decision =
            ScreenSettlePolicy.evaluate(now, startedAt, snapshot.first, snapshot.last, config);
        if (decision.terminal() || now >= deadlineElapsed) {
          ScreenSettlePolicy.State state =
              decision.terminal() ? decision.state() : ScreenSettlePolicy.State.TIMED_OUT;
          return ScreenSettlePolicy.outcome(
              state, startedAt, now, snapshot.first, snapshot.last, snapshot.events);
        }
        if (!keepWaiting.getAsBoolean())
          return ScreenSettlePolicy.outcome(
              ScreenSettlePolicy.State.TIMED_OUT,
              startedAt,
              now,
              snapshot.first,
              snapshot.last,
              snapshot.events);
        long wait = Math.min(decision.nextCheckInMillis(), deadlineElapsed - now);
        if (wait <= 0) continue;
        try {
          // 等待会被新事件唤醒；超时后重新判定，绝不忙轮询。
          wait(wait);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return ScreenSettlePolicy.outcome(
              ScreenSettlePolicy.State.TIMED_OUT,
              startedAt,
              clock.getAsLong(),
              snapshot.first,
              snapshot.last,
              snapshot.events);
        }
      }
    }
  }

  private Snapshot scan(long startedAt, String packageName, long windowId) {
    long first = -1L;
    long last = -1L;
    int count = 0;
    for (int i = 0; i < size; i++) {
      int slot = (next - 1 - i + MAX_EVENTS * 2) % MAX_EVENTS;
      long at = times[slot];
      if (at < startedAt) continue;
      if (!matches(windows[slot], packages[slot], packageName, windowId)) continue;
      if (first < 0 || at < first) first = at;
      if (at > last) last = at;
      count++;
    }
    return new Snapshot(first, last, count);
  }

  private static boolean matches(
      long eventWindow, String eventPackage, String packageName, long windowId) {
    if (windowId >= 0 && eventWindow == windowId) return true;
    return packageName != null && !packageName.isEmpty() && packageName.equals(eventPackage);
  }

  private synchronized void clearEvents() {
    java.util.Arrays.fill(times, 0L);
    java.util.Arrays.fill(windows, 0L);
    java.util.Arrays.fill(packages, null);
    size = 0;
    next = 0;
  }

  private record Snapshot(long first, long last, int events) {}
}
