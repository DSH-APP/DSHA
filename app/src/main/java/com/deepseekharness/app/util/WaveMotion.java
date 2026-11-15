package com.deepseekharness.app.util;

/**
 * 插件页头部波浪进度（方案 W3「弹性追赶」）的纯时间 / 几何推导；原型见 docs/design/plugin-header-20261101/preview.html。
 *
 * <p>波浪几何沿用启动页 ShellActivityTrack（波长 26–28dp、振幅 3dp 量级、线宽 4dp），在其上加入：
 *
 * <ul>
 *   <li>不确定态：头尾各走一条缓动曲线，段长在 8%–60% 间伸缩，先快后慢；
 *   <li>确定态：只跟随真实 percent，换挡时用强回弹曲线追赶；波纹速度随最近一次跳变的幅度加速，停滞后放缓；
 *   <li>收束：从当前段位置铺满（成功）或停在真实 percent（失败），振幅压到 0，再按既有时长停留淡出。
 * </ul>
 *
 * <p>只做数学，不 import Android；显示值可因回弹短暂越过目标，但读屏与状态始终报告真实 percent。
 */
public final class WaveMotion {
  private WaveMotion() {}

  /** 不确定态一周期。 */
  public static final long INDETERMINATE_CYCLE_MS = 1750L;

  /** 真实 percent 变化时的追赶时长（W3 比标准 240ms 长，回弹才看得见）。 */
  public static final long FILL_MS = 680L;

  /** 收束：铺满并压平。 */
  public static final long SETTLE_MS = 520L;

  /** 成功 / 失败停留与淡出，沿用 PluginTaskProgress。 */
  public static final long SUCCESS_HOLD_MS = 700L;

  public static final long FAILURE_HOLD_MS = 900L;
  public static final long FADE_MS = 240L;

  /** 段长下限（占轨道比例），避免头尾重合时消失。 */
  public static final float MIN_SEGMENT = 0.08f;

  /** 两端渐收振幅的距离（dp），段首尾不出现断口。 */
  public static final float TAPER_DP = 14f;

  private static final Bezier HEAD = new Bezier(0.2f, 0f, 0.1f, 1f);
  private static final Bezier TAIL = new Bezier(0.6f, 0f, 0.25f, 1f);
  private static final Bezier SPRING = new Bezier(0.3f, 1.7f, 0.55f, 1f);

  /** 不确定态在相位 phase（0..1）时波浪段的 [起点, 终点]，单位为轨道比例；可越出 0..1，由绘制裁剪。 */
  public static float[] indeterminateSegment(float phase) {
    float k = clamp01(phase);
    float tail = -0.1f + 1.2f * TAIL.y(k);
    float head = -0.1f + 1.2f * HEAD.y(k);
    if (head - tail < MIN_SEGMENT) head = tail + MIN_SEGMENT;
    return new float[] {tail, head};
  }

  /** 不确定态波纹速度倍率：头部推进越快波纹越急，范围约 0.7–1.9。 */
  public static float indeterminateRippleSpeed(float phase) {
    float k = clamp01(phase);
    float next = Math.min(1f, k + 0.02f);
    float slope = (HEAD.y(next) - HEAD.y(k)) / Math.max(0.0001f, next - k);
    return 0.7f + 0.56f * Math.min(2.2f, Math.max(0f, slope));
  }

  /** 确定态从 from 追赶到 to 的显示值；elapsed 超过 FILL_MS 即为 to。 */
  public static float fill(float from, float to, long elapsedMs) {
    if (elapsedMs <= 0) return from;
    if (elapsedMs >= FILL_MS) return to;
    return from + (to - from) * SPRING.y(elapsedMs / (float) FILL_MS);
  }

  /**
   * 确定态波纹速度倍率：最近一次跳变越大、越新，波纹越快；超过 1.2s 没有新数据时放缓到 0.55，表达「停滞」。
   *
   * @param jump 最近一次跳变的幅度（0..1）
   * @param sinceJumpMs 距最近一次跳变的时间
   */
  public static float determinateRippleSpeed(float jump, long sinceJumpMs) {
    float burst = 2.2f * Math.min(1f, Math.abs(jump) * 6f) * Math.max(0f, 1f - sinceJumpMs / 900f);
    float base = sinceJumpMs <= 1200L ? 1f : Math.max(0.55f, 1f - (sinceJumpMs - 1200L) / 1600f);
    return base + burst;
  }

  /** 确定态振幅倍率：越接近完成越平，100% 时剩 45%，再交给收束压到 0。 */
  public static float determinateAmplitude(float fraction) {
    float p = clamp01(fraction);
    return 1f - 0.55f * p * p * p;
  }

  /** 收束进度（0..1，ease-out）；关闭动画时直接为 1。 */
  public static float settle(long elapsedMs, boolean animate) {
    if (!animate) return 1f;
    float t = clamp01(elapsedMs / (float) SETTLE_MS);
    return 1f - (1f - t) * (1f - t);
  }

  /** 收束整体透明度：铺满 + 停留后淡出；关闭动画时停留结束直接为 0。 */
  public static float terminalAlpha(long elapsedMs, boolean success, boolean animate) {
    long hold = (animate ? SETTLE_MS : 0L) + (success ? SUCCESS_HOLD_MS : FAILURE_HOLD_MS);
    if (elapsedMs <= hold) return 1f;
    if (!animate) return 0f;
    return Math.max(0f, 1f - (elapsedMs - hold) / (float) FADE_MS);
  }

  /** 收束全过程时长，用于安排隐藏。 */
  public static long terminalDuration(boolean success, boolean animate) {
    return (animate ? SETTLE_MS + FADE_MS : 0L) + (success ? SUCCESS_HOLD_MS : FAILURE_HOLD_MS);
  }

  /** 段内某点的振幅包络：距两端 taper 以内线性渐收。 */
  public static float taper(float x, float start, float end, float taper) {
    if (end <= start || taper <= 0f) return 1f;
    return clamp01(Math.min(x - start, end - x) / taper);
  }

  public static float clamp01(float value) {
    return value < 0f ? 0f : Math.min(1f, value);
  }

  /** cubic-bezier(x1, y1, x2, y2)；x 必须单调（x1、x2 ∈ [0,1]），y 允许越界以表达回弹。 */
  public static final class Bezier {
    private final float x1, y1, x2, y2;

    public Bezier(float x1, float y1, float x2, float y2) {
      if (x1 < 0f || x1 > 1f || x2 < 0f || x2 > 1f) throw new IllegalArgumentException("BEZIER_X");
      this.x1 = x1;
      this.y1 = y1;
      this.x2 = x2;
      this.y2 = y2;
    }

    public float y(float x) {
      if (x <= 0f) return 0f;
      if (x >= 1f) return 1f;
      return curve(solve(x), y1, y2);
    }

    /** Newton 迭代，导数过小时退回二分；x 单调保证解唯一。 */
    private float solve(float x) {
      float t = x;
      for (int i = 0; i < 8; i++) {
        float error = curve(t, x1, x2) - x;
        if (Math.abs(error) < 1e-5f) return t;
        float slope = slope(t, x1, x2);
        if (Math.abs(slope) < 1e-6f) break;
        t = Math.max(0f, Math.min(1f, t - error / slope));
      }
      float low = 0f, high = 1f;
      t = x;
      for (int i = 0; i < 24; i++) {
        float value = curve(t, x1, x2);
        if (Math.abs(value - x) < 1e-5f) break;
        if (value < x) low = t;
        else high = t;
        t = (low + high) / 2f;
      }
      return t;
    }

    private static float curve(float t, float a, float b) {
      float u = 1f - t;
      return 3f * a * t * u * u + 3f * b * t * t * u + t * t * t;
    }

    private static float slope(float t, float a, float b) {
      float u = 1f - t;
      return 3f * a * u * u + 6f * (b - a) * t * u + 3f * (1f - b) * t * t;
    }
  }
}
