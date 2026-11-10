package com.deepseekharness.app.util;

/**
 * 左滑动作（F2）的纯几何与方向判定。
 *
 * <p>动作条固定在行尾（end）侧：LTR 时向左滑露出，RTL 时向右滑露出。拖动位移超过动作条宽度
 * 后按阻尼继续跟手（不会无限拉长），松手位移达到动作条宽度的 {@link #OPEN_FRACTION} 才停在
 * 打开位置；否则回弹关闭。组件只负责露出动作，破坏性动作必须由宿主再次确认（见
 * {@code ui/SwipeAction}），滑动本身从不执行动作。
 *
 * <p>纯逻辑，不 import Android API；像素、阈值都由调用方按当前密度换算后传入。
 */
public final class SwipeGesture {
  /** 松手后停在打开位置所需的最小位移比例（相对动作条宽度）。 */
  public static final float OPEN_FRACTION = 0.45f;

  /** 超出动作条宽度后的跟手比例：继续拖只会再位移 30%，避免整行被拉出屏幕。 */
  public static final float OVERDRAG_RESPONSE = 0.3f;

  /** 横向手势判定：水平位移必须超过竖直位移的 1.5 倍，避免和列表纵向滚动抢事件。 */
  public static final float HORIZONTAL_RATIO = 1.5f;

  private SwipeGesture() {}

  /** 是否够得上一次横向拖动（超过系统 touch slop 且明显偏水平）。 */
  public static boolean horizontal(float dx, float dy, int touchSlop) {
    return Math.abs(dx) > Math.max(0, touchSlop) && Math.abs(dx) > Math.abs(dy) * HORIZONTAL_RATIO;
  }

  /** 该滑动方向是否露出动作条：LTR 向左（dx 为负），RTL 向右（dx 为正）。 */
  public static boolean revealDirection(float dx, boolean rtl) {
    return rtl ? dx > 0f : dx < 0f;
  }

  /**
   * 把手指位移换算成行的水平位移（与 {@code View.setTranslationX} 同一坐标系）。
   *
   * <p>反方向的位移一律为 0（动作条只在行尾一侧）；超过动作条宽度后按
   * {@link #OVERDRAG_RESPONSE} 阻尼。
   */
  public static float dragOffset(float rawDx, int stripWidth, boolean rtl) {
    // 先换算到 LTR 坐标系（负值 = 向行尾滑），算出位移后再按 RTL 镜像回去。
    float signed = rtl ? -rawDx : rawDx;
    if (signed > 0f) return 0f;
    float limit = Math.max(0, stripWidth);
    float offset = -signed <= limit ? signed : -(limit + (-signed - limit) * OVERDRAG_RESPONSE);
    return rtl ? -offset : offset;
  }

  /** 松手时位移是否足以停在打开位置。 */
  public static boolean opens(float offset, int stripWidth) {
    if (stripWidth <= 0) return false;
    return Math.abs(offset) >= stripWidth * OPEN_FRACTION;
  }

  /** 目标位移：打开为整条动作条宽度，关闭为 0；RTL 取反。 */
  public static float settleOffset(boolean open, int stripWidth, boolean rtl) {
    float target = open ? -Math.max(0, stripWidth) : 0f;
    return rtl ? -target : target;
  }
}
