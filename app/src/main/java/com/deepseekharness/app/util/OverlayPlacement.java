package com.deepseekharness.app.util;

/**
 * 悬浮助手拖动位置的纯计算。窗口以右下角为锚点（gravity = BOTTOM|END），x/y 是距右边、下边的偏移，
 * 所以手指向左、向上移动时偏移增大。
 */
public final class OverlayPlacement {
  private OverlayPlacement() {}

  /** 手指移动超过触摸阈值才算拖动；否则仍是一次点按（展开）。 */
  public static boolean isDrag(float dx, float dy, int touchSlop) {
    return dx * dx + dy * dy > (float) touchSlop * touchSlop;
  }

  /** 拖动后的偏移：右下锚点下，屏幕坐标增量取反。 */
  public static int offset(int startOffset, float startRaw, float currentRaw) {
    return Math.round(startOffset - (currentRaw - startRaw));
  }

  /**
   * 把偏移限制在屏幕内，至少保留 {@code margin} 边距。窗口比可用区域还大时贴在锚点一侧。
   *
   * @param size 窗口在该方向的尺寸（像素）
   * @param screen 屏幕在该方向的尺寸（像素）
   */
  public static int clamp(int offset, int size, int screen, int margin) {
    int max = screen - size - margin;
    if (max < margin) return margin;
    return Math.max(margin, Math.min(offset, max));
  }
}
