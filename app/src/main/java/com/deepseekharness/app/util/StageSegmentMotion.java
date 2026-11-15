package com.deepseekharness.app.util;

/**
 * 维护页 / 部署进度分段条的动效推导：只做时间与状态数学，不 import Android。
 *
 * <ul>
 *   <li>段由空变满时从左向右填充；同一次渲染里多段同时变满（首次显示、跳步）按序错开；
 *   <li>运行中的当前段叠一条往复流光，表示该阶段仍在进行，不代表段内百分比；
 *   <li>结束时（成功 / 未就绪 / 失败）段颜色交叉淡变，不重播填充。
 * </ul>
 *
 * <p>分段条每秒随页面刷新重建；{@link Tracker} 由页面持有，以绝对时间记录每段的起始时刻，新视图接着画，
 * 不会因重建从头播放。系统关闭动画时调用方直接画终态。
 */
public final class StageSegmentMotion {
  private StageSegmentMotion() {}

  /** 单段填充时长。 */
  public static final long FILL_MS = 420L;

  /** 同批多段的错开间隔。 */
  public static final long STAGGER_MS = 70L;

  /** 结果变色时长。 */
  public static final long COLOR_MS = 260L;

  /** 流光一个来回的周期。 */
  public static final long SHIMMER_CYCLE_MS = 1600L;

  /** 流光带宽（占段宽比例）。 */
  public static final float SHIMMER_WIDTH = 0.45f;

  private static final WaveMotion.Bezier STANDARD = new WaveMotion.Bezier(0.22f, 1f, 0.36f, 1f);

  /** 段是否已着色（满段）：除 TODO 以外都画满，与原静态分段条一致。 */
  public static boolean filled(DeployStages.Step step) {
    return step != null && step != DeployStages.Step.TODO;
  }

  /** 当前段是否叠流光：只有任务仍在运行时。 */
  public static boolean shimmer(DeployStages.Phase phase, DeployStages.Step step) {
    return phase == DeployStages.Phase.RUNNING && step == DeployStages.Step.CURRENT;
  }

  /** 已开始 elapsed 毫秒的填充比例（0..1，标准曲线）；负值表示还在错开等待。 */
  public static float fill(long elapsedMs) {
    if (elapsedMs <= 0) return 0f;
    if (elapsedMs >= FILL_MS) return 1f;
    return clamp01(STANDARD.y(elapsedMs / (float) FILL_MS));
  }

  /** 变色进度（0..1）。 */
  public static float colorMix(long elapsedMs) {
    if (elapsedMs <= 0) return 0f;
    if (elapsedMs >= COLOR_MS) return 1f;
    return clamp01(STANDARD.y(elapsedMs / (float) COLOR_MS));
  }

  /**
   * 流光中心位置（段宽比例）：从左外侧扫到右外侧，再停顿一小段；以绝对时钟取相位，重建视图不跳。
   *
   * @return -SHIMMER_WIDTH .. 1+SHIMMER_WIDTH
   */
  public static float shimmerCenter(long uptimeMs) {
    long cycle = SHIMMER_CYCLE_MS;
    float phase = (float) (((uptimeMs % cycle) + cycle) % cycle) / cycle;
    // 前 75% 扫过，后 25% 停在右外侧，避免持续闪动。
    float sweep = Math.min(1f, phase / 0.75f);
    float eased = sweep * sweep * (3f - 2f * sweep);
    return -SHIMMER_WIDTH + (1f + 2f * SHIMMER_WIDTH) * eased;
  }

  static float clamp01(float value) {
    return value < 0f ? 0f : (value > 1f ? 1f : value);
  }

  /** 每段的填充起点与颜色交接记录；按任务与模式区分，换任务时首次显示重新错开播放。 */
  public static final class Tracker {
    private String key = null;
    private long[] fillAt = new long[0];
    private boolean[] wasFilled = new boolean[0];
    private int[] color = new int[0];
    private int[] fromColor = new int[0];
    private long[] colorAt = new long[0];

    /**
     * 记录一次渲染的状态。
     *
     * @param taskKey 任务身份（任务号 + 模式）；变化时视为全新分段条
     * @param filled 每段是否满段
     * @param colors 每段目标颜色（ARGB）
     * @param now 当前单调时钟
     */
    public void update(String taskKey, boolean[] filled, int[] colors, long now) {
      int n = filled.length;
      boolean reset = key == null || !key.equals(taskKey) || fillAt.length != n;
      if (reset) {
        key = taskKey;
        fillAt = new long[n];
        wasFilled = new boolean[n];
        color = colors.clone();
        fromColor = colors.clone();
        colorAt = new long[n];
      }
      int batch = 0;
      for (int i = 0; i < n; i++) {
        if (filled[i] && !wasFilled[i]) {
          fillAt[i] = now + batch++ * STAGGER_MS;
        }
        wasFilled[i] = filled[i];
        if (!reset && colors[i] != color[i]) {
          // 从当前显示色（可能仍在淡变中）接到新色。
          fromColor[i] = displayColor(i, now);
          color[i] = colors[i];
          colorAt[i] = now;
        }
      }
    }

    public int size() {
      return fillAt.length;
    }

    /** 第 i 段填充比例；未满段为 0。 */
    public float fillFraction(int i, long now) {
      if (i < 0 || i >= fillAt.length || !wasFilled[i]) return 0f;
      return fill(now - fillAt[i]);
    }

    /** 第 i 段当前显示色。 */
    public int displayColor(int i, long now) {
      if (i < 0 || i >= color.length) return 0;
      return mix(fromColor[i], color[i], colorMix(now - colorAt[i]));
    }

    /** 是否还有填充或变色动画没结束（不含流光）。 */
    public boolean animating(long now) {
      for (int i = 0; i < fillAt.length; i++) {
        if (wasFilled[i] && now - fillAt[i] < FILL_MS) return true;
        if (now - colorAt[i] < COLOR_MS) return true;
      }
      return false;
    }
  }

  /** ARGB 逐通道线性插值。 */
  public static int mix(int from, int to, float t) {
    float k = clamp01(t);
    if (k <= 0f) return from;
    if (k >= 1f) return to;
    int result = 0;
    for (int shift = 0; shift <= 24; shift += 8) {
      int a = (from >>> shift) & 0xff;
      int b = (to >>> shift) & 0xff;
      result |= (Math.round(a + (b - a) * k) & 0xff) << shift;
    }
    return result;
  }
}
