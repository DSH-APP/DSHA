package com.deepseekharness.app.util;

/**
 * 维护空间预估的饱和加法：只用于在复制前给出保守需求，溢出时返回 {@link Long#MAX_VALUE} 而不是回绕成
 * 负数。数据量按两份计（快照副本与还原暂存），另加固定余量。
 */
public final class MaintenanceSpaceRequirement {
  private MaintenanceSpaceRequirement() {}

  public static final long HEADROOM = 64L * 1024 * 1024;

  public static long preflight(long expandedEnvironmentBytes, long dataBytes) {
    return sum(nonNegative(expandedEnvironmentBytes), doubled(nonNegative(dataBytes)), HEADROOM);
  }

  public static long snapshotReady(long expandedEnvironmentBytes, long snapshotBytes) {
    return sum(
        nonNegative(expandedEnvironmentBytes), doubled(nonNegative(snapshotBytes)), HEADROOM);
  }

  private static long nonNegative(long value) {
    return Math.max(0, value);
  }

  private static long doubled(long value) {
    return value > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : value * 2;
  }

  private static long sum(long... values) {
    long total = 0;
    for (long value : values) {
      if (value < 0 || total > Long.MAX_VALUE - value) return Long.MAX_VALUE;
      total += value;
    }
    return total;
  }
}
