package com.deepseekharness.app.util;

import java.util.Arrays;

/**
 * 实体屏特权执行（T130 Phase A）的纯逻辑规则：帧配置、坐标、动作参数、动作凭据与 benchmark 统计。
 *
 * <p>与虚拟屏的关键差别写在 {@link #credentialRejection} 上：静态虚拟屏要求"最新帧"，而游戏画面在连续变化，
 * 若坚持最新帧就永远无法动作。实体屏改为「调用方确实看过的一帧 + 30 秒 + 动作只消费一次 + 目标代次不变」；
 * 动作回执同时签发新帧与新凭据，可以连续动作而不必每步重新取帧。
 *
 * <p>本类不读 Android API，也不做任何授权决定：能力开关、任务身份与屏幕确认策略由桥侧执行。
 */
public final class PrivilegedScreenPolicy {
  private PrivilegedScreenPolicy() {}

  /** 帧通道默认值；真机 benchmark 报告必须写清实际使用的配置。 */
  public static final int DEFAULT_MAX_WIDTH = 1280;

  public static final int DEFAULT_JPEG_QUALITY = 60;
  public static final int DEFAULT_MAX_FPS = 6;

  public static final int MIN_MAX_WIDTH = 320;
  public static final int MAX_MAX_WIDTH = 1920;
  public static final int MIN_JPEG_QUALITY = 30;
  public static final int MAX_JPEG_QUALITY = 90;
  public static final int MIN_MAX_FPS = 1;
  public static final int MAX_MAX_FPS = 10;

  /** 动作凭据有效期；与实体主屏 /app/ui 的 observationId 语义一致。 */
  public static final long OBSERVATION_WINDOW_MS = 30_000;

  /** 一次滑动的最长时长，与虚拟屏输入保持同一预算。 */
  public static final int MAX_SWIPE_MS = 3_000;

  /** 动作后等待新帧的时长上限；0 表示不等帧，只签发当前帧的新观察。 */
  public static final int MAX_FRAME_WAIT_MS = 500;

  public static final int DEFAULT_FRAME_WAIT_MS = 250;

  /** benchmark 单次请求的边界：n ≤ 20，且预估总时长不超过 8 秒。 */
  public static final int MAX_BENCHMARK_SAMPLES = 20;

  public static final long MAX_BENCHMARK_BUDGET_MS = 8_000;

  /** 近黑帧判定阈值：采样点中至少 98% 为近黑时标记 SECURE_OR_BLACK（保守启发式，不是安全证明）。 */
  public static final int BLACK_SAMPLE_RATIO_PERCENT = 98;

  /**
   * 允许注入的按键：与虚拟屏固定动词表一致（返回、主页、回车、退格）。
   *
   * <p>不开放任意 keycode：游戏摇杆/触摸由 tap/swipe 承担，系统导航键之外的按键没有需求，
   * 放开的越多，越可能被用来操作安全界面。
   */
  public static boolean keyAllowed(int keycode) {
    return keycode == 3 || keycode == 4 || keycode == 66 || keycode == 67;
  }

  /** 规范化后的帧通道配置。非法值不静默夹取，由调用方按 {@link #configRejection} 拒绝。 */
  public record FrameConfig(int maxWidth, int jpegQuality, int maxFps) {
    public static FrameConfig defaults() {
      return new FrameConfig(DEFAULT_MAX_WIDTH, DEFAULT_JPEG_QUALITY, DEFAULT_MAX_FPS);
    }

    public boolean equals(FrameConfig other) {
      return other != null
          && maxWidth == other.maxWidth
          && jpegQuality == other.jpegQuality
          && maxFps == other.maxFps;
    }
  }

  public static String configRejection(Integer maxWidth, Integer quality, Integer fps) {
    if (maxWidth != null && (maxWidth < MIN_MAX_WIDTH || maxWidth > MAX_MAX_WIDTH))
      return "PRIV_INVALID_FRAME_WIDTH";
    if (quality != null && (quality < MIN_JPEG_QUALITY || quality > MAX_JPEG_QUALITY))
      return "PRIV_INVALID_JPEG_QUALITY";
    if (fps != null && (fps < MIN_MAX_FPS || fps > MAX_MAX_FPS)) return "PRIV_INVALID_MAX_FPS";
    return null;
  }

  /**
   * 采样尺寸：保持屏幕宽高比，长边不超过 maxWidth；宽高至少 2 像素，避免 0 尺寸 Surface。
   */
  public static int[] captureSize(int screenWidth, int screenHeight, int maxWidth) {
    if (screenWidth <= 0 || screenHeight <= 0 || maxWidth < MIN_MAX_WIDTH)
      throw new IllegalArgumentException("PRIV_DISPLAY_SIZE_INVALID");
    if (screenWidth <= maxWidth && screenHeight <= maxWidth)
      return new int[] {screenWidth, screenHeight};
    float scale = maxWidth / (float) Math.max(screenWidth, screenHeight);
    return new int[] {
      Math.max(2, Math.round(screenWidth * scale)), Math.max(2, Math.round(screenHeight * scale))
    };
  }

  /** 坐标是否在本帧范围内；越界、NaN 与无穷都拒绝。 */
  public static String coordinateRejection(float x, float y, int width, int height) {
    if (width <= 0 || height <= 0) return "PRIV_FRAME_SIZE_INVALID";
    if (!Float.isFinite(x) || !Float.isFinite(y)) return "PRIV_INVALID_COORDINATE";
    if (x < 0 || y < 0 || x > width || y > height) return "PRIV_COORDINATE_OUT_OF_BOUNDS";
    return null;
  }

  /** 帧图像坐标 → display 0 的注入坐标；钳制在屏幕内，避免边缘取整越界。 */
  public static float[] map(
      float x, float y, int frameWidth, int frameHeight, int screenWidth, int screenHeight) {
    if (frameWidth <= 0 || frameHeight <= 0 || screenWidth <= 0 || screenHeight <= 0)
      throw new IllegalArgumentException("PRIV_DISPLAY_SIZE_INVALID");
    return new float[] {
      clamp(x * screenWidth / frameWidth, 0, screenWidth),
      clamp(y * screenHeight / frameHeight, 0, screenHeight)
    };
  }

  private static float clamp(float value, float min, float max) {
    return value < min ? min : Math.min(value, max);
  }

  public static String swipeDurationRejection(int ms) {
    return ms < 1 || ms > MAX_SWIPE_MS ? "PRIV_INVALID_DURATION" : null;
  }

  public static String frameWaitRejection(int ms) {
    return ms < 0 || ms > MAX_FRAME_WAIT_MS ? "PRIV_INVALID_FRAME_WAIT" : null;
  }

  /** 观察凭据格式：Core 签发 UUID；空串或其它格式一律拒绝。 */
  public static boolean validObservationId(String value) {
    return value != null
        && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  /**
   * 动作凭据判定：返回 null 放行，否则返回稳定拒绝码。
   *
   * @param requestedFrameSeq 调用方声称看过的帧号
   * @param requestedObservation 调用方持有的观察 id
   * @param observedFrameSeq Core 最近一次签发的观察帧号（-1 表示没有未消费的观察）
   * @param observedObservation Core 最近一次签发的观察 id
   * @param ageMs 观察签发到动作请求之间的时长（同一单调时钟）
   * @param requestedGeneration 调用方绑定的显示代次
   * @param currentGeneration 当前显示代次
   */
  public static String credentialRejection(
      long requestedFrameSeq,
      String requestedObservation,
      long observedFrameSeq,
      String observedObservation,
      long ageMs,
      String requestedGeneration,
      String currentGeneration) {
    if (requestedGeneration == null || !requestedGeneration.equals(currentGeneration))
      return "PRIV_STALE_GENERATION";
    if (!validObservationId(requestedObservation)) return "PRIV_FRAME_NOT_OBSERVED";
    if (observedFrameSeq <= 0 || !requestedObservation.equals(observedObservation))
      return "PRIV_FRAME_NOT_OBSERVED";
    if (requestedFrameSeq != observedFrameSeq) return "PRIV_FRAME_NOT_OBSERVED";
    return ageMs > OBSERVATION_WINDOW_MS ? "PRIV_OBSERVATION_TIMEOUT" : null;
  }

  /** 目标包名变化或无法核验时拒绝注入：宁可未发送，也不对未知窗口动手。 */
  public static String targetRejection(
      String expectedPackage, String actualPackage, boolean verified) {
    if (expectedPackage == null || expectedPackage.isEmpty()) return null;
    if (!verified || actualPackage == null || actualPackage.isEmpty())
      return "PRIV_TARGET_UNVERIFIABLE";
    return expectedPackage.equals(actualPackage) ? null : "PRIV_TARGET_CHANGED";
  }

  /** 近黑帧判定：采样点不足视为"不可判定"，不标 SECURE_OR_BLACK。 */
  public static boolean secureOrBlack(int nearBlackSamples, int totalSamples) {
    if (totalSamples <= 0 || nearBlackSamples < 0 || nearBlackSamples > totalSamples) return false;
    return nearBlackSamples * 100L >= (long) totalSamples * BLACK_SAMPLE_RATIO_PERCENT;
  }

  /**
   * benchmark 参数边界：除单次上限外，还要保证一次请求的总预算（按每次等待加固定开销估算）
   * 不超过传输层 12 秒响应预算的一半左右，避免 benchmark 自己撞上传输超时。
   */
  public static String benchmarkRejection(int samples, int waitMs) {
    if (samples < 1 || samples > MAX_BENCHMARK_SAMPLES) return "PRIV_INVALID_BENCHMARK_SAMPLES";
    String wait = frameWaitRejection(waitMs);
    if (wait != null) return wait;
    long estimate = (long) samples * (waitMs + 400L);
    return estimate > MAX_BENCHMARK_BUDGET_MS ? "PRIV_BENCHMARK_BUDGET_EXCEEDED" : null;
  }

  /** 一次 benchmark 的统计摘要（毫秒，-1 样本忽略）。 */
  public record Summary(int count, long p50, long p95, long max, long min) {
    public static Summary of(long[] samples) {
      if (samples == null) return new Summary(0, -1, -1, -1, -1);
      long[] values = Arrays.stream(samples).filter(value -> value >= 0).sorted().toArray();
      if (values.length == 0) return new Summary(0, -1, -1, -1, -1);
      return new Summary(
          values.length,
          percentile(values, 50),
          percentile(values, 95),
          values[values.length - 1],
          values[0]);
    }

    /** 最近秩（nearest-rank）百分位，结果一定是真实样本，不做插值。 */
    public static long percentile(long[] sorted, int percent) {
      if (sorted == null || sorted.length == 0) return -1;
      int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
      return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }
  }
}
