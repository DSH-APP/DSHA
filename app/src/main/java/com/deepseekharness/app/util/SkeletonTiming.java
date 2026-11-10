package com.deepseekharness.app.util;

/** 有限骨架的时长边界（E4）：1–2 秒上限，到点必须停，不出现无限 shimmer。 */
public final class SkeletonTiming {
  /** 最短展示时长：再短的骨架只是闪一下，不如直接给真实状态。 */
  public static final long MIN_MS = 1000L;

  /** 上限：超过这个时间骨架不再动，页面改为静态“仍在读取”。 */
  public static final long MAX_MS = 2000L;

  public static final long DEFAULT_MS = 1500L;

  private SkeletonTiming() {}

  /** 把请求时长夹到 1–2 秒；非法值用默认值。 */
  public static long clampDuration(long requestedMs) {
    if (requestedMs <= 0) return DEFAULT_MS;
    return Math.max(MIN_MS, Math.min(MAX_MS, requestedMs));
  }

  /** 骨架必须停止的绝对时刻。 */
  public static long deadline(long startedAtMs, long durationMs) {
    return startedAtMs + clampDuration(durationMs);
  }

  public static boolean expired(long nowMs, long deadlineMs) {
    return nowMs >= deadlineMs;
  }

  public static long remaining(long nowMs, long deadlineMs) {
    return Math.max(0L, deadlineMs - nowMs);
  }
}
