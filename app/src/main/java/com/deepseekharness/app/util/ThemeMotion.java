package com.deepseekharness.app.util;

/**
 * 主题切换扩散与开机加载页的时序纯逻辑。
 *
 * <p>扩散只向外扩、不回缩：参考稿「45% → 25% → 100%」叠加过冲缓动，真机上新主题露出后又被旧截图
 * 盖回，看起来像回闪。现在用无过冲的 ease-out 三次曲线单调铺满。加载页至少完整播放一次描线，
 * 之后才允许上滑收起，避免冷启动快时只闪一帧。
 */
public final class ThemeMotion {
  /** 切换动画样式：圆形扩散（默认）、对角擦除、淡入淡出、直接切换。 */
  public static final String CIRCLE = "circle", DIAGONAL = "diagonal", FADE = "fade", NONE = "none";

  /** 主题页展示顺序。 */
  public static final String[] STYLES = {CIRCLE, DIAGONAL, FADE, NONE};

  /** 圆形扩散总时长（毫秒）。 */
  public static final long REVEAL_MS = 560L;

  /** 对角擦除要走完整条对角线并慢起慢停，略长；淡入淡出只改透明度，较短。 */
  public static final long DIAGONAL_MS = 1_000L, FADE_MS = 320L;

  /** 未知或已下线的值回到圆形扩散。 */
  public static String normalizeStyle(String value) {
    for (String style : STYLES) if (style.equals(value)) return style;
    return CIRCLE;
  }

  public static long durationMs(String style) {
    switch (normalizeStyle(style)) {
      case DIAGONAL:
        return DIAGONAL_MS;
      case FADE:
        return FADE_MS;
      case NONE:
        return 0L;
      default:
        return REVEAL_MS;
    }
  }

  /** 是否需要截图：直接切换不截图，省一屏内存。 */
  public static boolean needsCapture(String style) {
    return !NONE.equals(normalizeStyle(style));
  }

  /** 对角擦除柔边宽度：对角线长度的占比。 */
  static final float DIAGONAL_FEATHER = 0.18f;

  /**
   * 慢起慢停（ease-in-out 三次）：对角擦除要让人看清从角里出发、在对角收尾，不能像 ease-out 那样开头一闪而过。
   * 单调不减、无过冲；越界截到 [0, 1]，NaN 视为结束。
   */
  public static float easeInOut(float linear) {
    if (Float.isNaN(linear) || linear >= 1f) return 1f;
    if (linear <= 0f) return 0f;
    if (linear < 0.5f) return 4f * linear * linear * linear;
    float rest = -2f * linear + 2f;
    return 1f - rest * rest * rest / 2f;
  }

  /**
   * 对角擦除的柔边渐变端点 {x0, y0, x1, y1}（窗口坐标）：旧主题截图乘上这段从透明到不透明的渐变。
   *
   * <ul>
   *   <li>方向固定沿屏幕真实对角线：左上 → 右下；从右到左排版时镜像为右上 → 左下。不随点按位置跳角；
   *   <li>前沿垂直于该对角线，宽 {@link #DIAGONAL_FEATHER} 倍对角线长；
   *   <li>进度 0 时整条柔边在起点角之外（全是旧主题），进度 1 时在终点角之外（全是新主题）。
   * </ul>
   */
  public static float[] diagonalGradient(float width, float height, boolean rtl, float progress) {
    float startX = rtl ? width : 0f, endX = rtl ? 0f : width;
    float length = (float) Math.hypot(width, height);
    if (length <= 0f) return new float[] {0f, 0f, 0f, 0f};
    float ux = (endX - startX) / length, uy = height / length;
    float band = DIAGONAL_FEATHER * length;
    // 柔边后缘从 -band 走到 length：前缘从 0 走到 length + band。
    float trailing = -band + (length + band) * easeInOut(progress);
    float leading = trailing + band;
    return new float[] {startX + ux * trailing, uy * trailing, startX + ux * leading, uy * leading};
  }

  /** 扩散截图最长保留时间；超时视为重建未发生，丢弃截图。 */
  public static final long CAPTURE_TTL_MS = 3_000L;

  /** 冷启动加载页最短可见时长：光晕、描线与字标依次出现完毕。 */
  public static final long SPLASH_MIN_VISIBLE_MS = 1_200L;

  /** 加载页上滑淡出时长。 */
  public static final long SPLASH_EXIT_MS = 600L;

  private ThemeMotion() {}

  /**
   * 线性时间进度 {@code linear}（0–1）映射到半径占比：ease-out 三次曲线，单调不减、无过冲。
   *
   * <p>越界值截到 [0, 1]；NaN 视为结束，保证遮罩一定能被完全移开。
   */
  public static float revealFraction(float linear) {
    if (Float.isNaN(linear) || linear >= 1f) return 1f;
    if (linear <= 0f) return 0f;
    float rest = 1f - linear;
    return 1f - rest * rest * rest;
  }

  /** 从 (x, y) 覆盖整个 width × height 区域所需的最大半径（到最远角的距离）。 */
  public static float maxRadius(float width, float height, float x, float y) {
    float dx = Math.max(Math.abs(x), Math.abs(width - x));
    float dy = Math.max(Math.abs(y), Math.abs(height - y));
    return (float) Math.hypot(dx, dy);
  }

  /** 截图是否仍可用于重建后的扩散。时钟回拨视为过期。 */
  public static boolean captureFresh(long capturedAt, long now) {
    return now >= capturedAt && now - capturedAt <= CAPTURE_TTL_MS;
  }

  /**
   * 加载页还需等待多久才能开始收起。
   *
   * @param intro 本次是否播放了开场动画；未播放（重建、系统关闭动画）时不等待
   */
  public static long splashHoldMs(boolean intro, long shownAt, long now) {
    if (!intro) return 0L;
    long elapsed = now - shownAt;
    if (elapsed < 0) return SPLASH_MIN_VISIBLE_MS;
    return Math.max(0L, SPLASH_MIN_VISIBLE_MS - elapsed);
  }
}
