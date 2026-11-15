package com.deepseekharness.app.util;

/**
 * 只读识屏结果的显式元数据：来源、质量、是否不完整、读取耗时与回复时的年龄。
 *
 * <p>时间全部来自同一单调时钟（elapsedRealtime）；时钟倒退或缺失时记为 -1（未知），不猜测、不截成 0。
 * 年龄只描述“这份快照距采集完成过了多久”，不代表界面在这段时间内没有变化。
 */
public final class ScreenSenseMetadata {
  public static final long UNKNOWN = -1L;

  public final String source;
  public final String quality;
  public final boolean partial;
  public final String truncationReason;
  public final long readMs;
  public final long ageMs;

  private ScreenSenseMetadata(
      String source,
      String quality,
      boolean partial,
      String truncationReason,
      long readMs,
      long ageMs) {
    this.source = source;
    this.quality = quality;
    this.partial = partial;
    this.truncationReason = truncationReason;
    this.readMs = readMs;
    this.ageMs = ageMs;
  }

  /**
   * @param startedAt 开始遍历前的单调时间
   * @param capturedAt 遍历完成、目标复核通过后的单调时间
   * @param now 组装回复时的单调时间
   */
  public static ScreenSenseMetadata of(
      ScreenSenseState state, long startedAt, long capturedAt, long now) {
    if (state == null) throw new IllegalArgumentException("SCREEN_SENSE_STATE_MISSING");
    return new ScreenSenseMetadata(
        state.source,
        state.quality(),
        state.truncated,
        state.truncationReason,
        elapsed(startedAt, capturedAt),
        elapsed(capturedAt, now));
  }

  private static long elapsed(long from, long to) {
    if (from < 0 || to < 0 || to < from) return UNKNOWN;
    return to - from;
  }
}
