package com.deepseekharness.app.plugins;

/**
 * 文件失效信号的纯状态合并器；时间由调用者提供（单调毫秒），调度和扫描均由 Engine 负责。
 *
 * <p>首事件固定本批的最迟发起边界，事件风暴不会不断延后。完成时间还包含 Engine 的有界 扫描耗时；若上一轮扫描已超时，此类不会冒充扫描完成，也不会并发启动第二轮。
 */
public final class PluginChangeCoalescer {
  public static final long DEFAULT_DEBOUNCE_MS = 200;
  public static final long DEFAULT_MAX_WAIT_MS = 2000;
  public static final long DEFAULT_MIN_SCAN_INTERVAL_MS = 750;

  private final long debounceMs;
  private final long maxWaitMs;
  private final long minScanIntervalMs;
  private long firstDirtyAt = -1;
  private long lastDirtyAt = -1;
  private long lastScanStartedAt = -1;
  private long lastNow = -1;
  private boolean immediate;
  private boolean scanning;

  public PluginChangeCoalescer() {
    this(DEFAULT_DEBOUNCE_MS, DEFAULT_MAX_WAIT_MS, DEFAULT_MIN_SCAN_INTERVAL_MS);
  }

  public PluginChangeCoalescer(long debounceMs, long maxWaitMs, long minScanIntervalMs) {
    if (debounceMs < 0
        || minScanIntervalMs < 0
        || maxWaitMs < debounceMs
        || maxWaitMs < minScanIntervalMs) {
      throw new IllegalArgumentException("合并截止不得小于防抖或最小扫描间隔");
    }
    this.debounceMs = debounceMs;
    this.maxWaitMs = maxWaitMs;
    this.minScanIntervalMs = minScanIntervalMs;
  }

  public synchronized void markDirty(long now) {
    now = time(now);
    if (firstDirtyAt < 0) firstDirtyAt = now;
    lastDirtyAt = now;
  }

  /** 前台恢复、来源换代及操作结束的补偿请求仍遵守最小扫描间隔。 */
  public synchronized void requestNow(long now) {
    markDirty(now);
    immediate = true;
  }

  /** -1 表示无待办或已有扫描；0 表示现在可开始。不可将 -1 当作零延迟重新轮询。 */
  public synchronized long nextDelayMillis(long now) {
    now = time(now);
    if (scanning || firstDirtyAt < 0) return -1;
    long due =
        immediate
            ? firstDirtyAt
            : Math.min(add(lastDirtyAt, debounceMs), add(firstDirtyAt, maxWaitMs));
    if (lastScanStartedAt >= 0) due = Math.max(due, add(lastScanStartedAt, minScanIntervalMs));
    return due <= now ? 0 : due - now;
  }

  /** 仅消耗本轮开始前的信号；开始后的 markDirty 自动属于下一轮。 */
  public synchronized boolean beginScan(long now) {
    now = time(now);
    if (nextDelayMillis(now) != 0) return false;
    scanning = true;
    lastScanStartedAt = now;
    firstDirtyAt = lastDirtyAt = -1;
    immediate = false;
    return true;
  }

  /** 成功、失败、取消都须结束占用；不清除扫描期间到达的下一批信号。 */
  public synchronized void scanFinished(long now) {
    time(now);
    scanning = false;
  }

  public synchronized boolean hasPending() {
    return firstDirtyAt >= 0;
  }

  public synchronized boolean isScanning() {
    return scanning;
  }

  public synchronized long firstDirtyAt() {
    return firstDirtyAt;
  }

  /** 下一批首事件的固定截止；没有下一批时返回 -1。 */
  public synchronized long deadlineAt() {
    return firstDirtyAt < 0 ? -1 : add(firstDirtyAt, maxWaitMs);
  }

  private long time(long now) {
    if (now < 0) throw new IllegalArgumentException("需要非负单调毫秒时间");
    lastNow = Math.max(lastNow, now);
    return lastNow;
  }

  private static long add(long value, long delta) {
    return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
  }
}
