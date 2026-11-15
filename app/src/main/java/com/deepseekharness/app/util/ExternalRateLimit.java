package com.deepseekharness.app.util;

/**
 * 进程内的最小调用间隔（单调时钟毫秒）。用于外接设备只读观察的限速：每次 watch 都要拉起一次专属 adb
 * server，连续高频调用会占用 root helper 槽位并打扰目标设备。
 *
 * <p>纯逻辑：允许时返回 0 并记录本次时刻；被限速时返回还需等待的毫秒数，不推进记录。时钟回拨（负数间隔）按“可以
 * 调用”处理并重新记时，避免永久拒绝。
 */
public final class ExternalRateLimit {
  private final long minGapMs;
  private boolean used;
  private long lastMs;

  public ExternalRateLimit(long minGapMs) {
    if (minGapMs < 0) throw new IllegalArgumentException("EXTERNAL_RATE_LIMIT_GAP");
    this.minGapMs = minGapMs;
  }

  public synchronized long acquire(long nowMs) {
    if (!used) {
      used = true;
      lastMs = nowMs;
      return 0L;
    }
    long elapsed = nowMs - lastMs;
    if (elapsed < 0) {
      lastMs = nowMs;
      return 0L;
    }
    if (elapsed < minGapMs) return minGapMs - elapsed;
    lastMs = nowMs;
    return 0L;
  }

  public long minGapMs() {
    return minGapMs;
  }
}
