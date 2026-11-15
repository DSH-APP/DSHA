package com.deepseekharness.app.util;

/**
 * 首次引导动效的纯逻辑：决定一次步骤显示用哪种过渡、子项错峰时序与上限。
 *
 * <p>只描述“该不该动、往哪动”，不碰 Android 视图；视图层（ui/OnboardingMotion）按结果执行。 原则：
 *
 * <ul>
 *   <li>系统关闭动画、旋转 / 分屏重建、同一步原位刷新（轮询、授权数变化）一律不动，避免打断焦点与朗读；
 *   <li>首次打开引导用较慢的整页上浮入场；前进从行尾方向滑入，后退从行首方向滑入；
 *   <li>错峰只作用于前几项，总时长有上限，不让用户等动画才能点按钮。
 * </ul>
 */
public final class OnboardingMotionPlan {
  /** 过渡种类。 */
  public enum Kind {
    /** 不播放（静态显示）。 */
    NONE,
    /** 首次进入引导：整页上浮淡入。 */
    INTRO,
    /** 前进到后面的步骤：内容从行尾方向滑入。 */
    FORWARD,
    /** 返回前面的步骤：内容从行首方向滑入。 */
    BACKWARD
  }

  /** 参与错峰的最多子项数；其余子项与最后一项同时出现。 */
  public static final int MAX_STAGGER = 6;

  /** 相邻子项的错峰间隔（毫秒）。 */
  public static final long STAGGER_MS = 35L;

  /** 单项淡入时长（毫秒）。 */
  public static final long ITEM_MS = 240L;

  /** 首次入场的单项时长（毫秒），略慢于步骤切换。 */
  public static final long INTRO_ITEM_MS = 360L;

  /** 首次入场的错峰间隔（毫秒）。 */
  public static final long INTRO_STAGGER_MS = 55L;

  private OnboardingMotionPlan() {}

  /**
   * 选择过渡种类。
   *
   * @param animationsEnabled 系统动画总开关
   * @param restored 是否为旋转 / 分屏 / 进程恢复后的重建（重建不应再演一遍入场）
   * @param firstShow 是否为本 Activity 实例的第一次显示
   * @param from 切换前的步骤
   * @param to 切换后的步骤
   */
  public static Kind choose(
      boolean animationsEnabled, boolean restored, boolean firstShow, int from, int to) {
    if (!animationsEnabled) return Kind.NONE;
    if (firstShow) return restored ? Kind.NONE : Kind.INTRO;
    if (to > from) return Kind.FORWARD;
    if (to < from) return Kind.BACKWARD;
    return Kind.NONE;
  }

  /**
   * 横向滑入的起点符号：+1 表示从右侧进入，-1 表示从左侧进入，0 表示不横移。 RTL 布局下前进 / 后退方向镜像。
   */
  public static int slideSign(Kind kind, boolean rtl) {
    int sign =
        switch (kind) {
          case FORWARD -> 1;
          case BACKWARD -> -1;
          default -> 0;
        };
    return rtl ? -sign : sign;
  }

  /** 第 {@code index} 个子项的启动延迟（毫秒）；超过上限的子项与最后一个错峰项同时出现。 */
  public static long delay(Kind kind, int index) {
    if (kind == Kind.NONE || index <= 0) return 0L;
    long step = kind == Kind.INTRO ? INTRO_STAGGER_MS : STAGGER_MS;
    return Math.min(index, MAX_STAGGER - 1) * step;
  }

  /** 单项时长（毫秒）。 */
  public static long duration(Kind kind) {
    return switch (kind) {
      case NONE -> 0L;
      case INTRO -> INTRO_ITEM_MS;
      default -> ITEM_MS;
    };
  }

  /**
   * 手机步骤条里需要“填充”动画的分段：从 {@code from} 前进到 {@code to} 时新点亮的分段 [from+1, to]。
   * 后退、原地或未播放时返回空区间（first &gt; last）。
   *
   * @return 长度为 2 的数组 {first, last}
   */
  public static int[] filledSegments(Kind kind, int from, int to) {
    if (kind == Kind.FORWARD && to > from) return new int[] {from + 1, to};
    if (kind == Kind.INTRO) return new int[] {0, to};
    return new int[] {1, 0};
  }
}
