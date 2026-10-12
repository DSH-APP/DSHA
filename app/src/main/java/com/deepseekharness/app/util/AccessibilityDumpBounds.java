package com.deepseekharness.app.util;

/**
 * 无障碍节点的矩形可用性。
 *
 * <p>文字节点可以零面积：{@code (0,370,0,499)} 这种只有高度的矩形仍是合法输入。只有反序矩形
 * （{@code right < left} 或 {@code bottom < top}）不是「空节点」而是噪声——它的四条边没有一条
 * 能用来定位，调用方拿它去点屏幕只会点到别处。主屏 dump 一直按这条不变量丢弃节点；虚拟屏
 * tree 直到 issue #143 才补上：同一份判定写在两处，只有一处跟着修。
 */
public final class AccessibilityDumpBounds {
  private AccessibilityDumpBounds() {}

  public static boolean ordered(int left, int top, int right, int bottom) {
    return right >= left && bottom >= top;
  }

  /**
   * 节点是否值得报给调用方：有可读文字或可操作能力，且矩形能用来定位。
   *
   * @param labeled 是否有文字或描述
   * @param actionable 是否可点、可编辑或可滚动（各调用方按自己的语义给定）
   */
  public static boolean reportable(
      boolean labeled, boolean actionable, int left, int top, int right, int bottom) {
    return (labeled || actionable) && ordered(left, top, right, bottom);
  }
}
