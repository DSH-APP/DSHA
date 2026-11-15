package com.deepseekharness.app.util;

/**
 * ⓘ 说明弹层（InfoHint）的形态与几何判定，单位全部为 dp。
 *
 * <p>原型见 docs/design/info-dialog-20261102/preview.html 方案 B：
 *
 * <ul>
 *   <li>宽 ≥ 600 且高 ≥ 480 且有锚点：贴着 ⓘ 的锚定气泡，无遮罩；
 *   <li>高 &lt; 480（手机横屏、矮分屏）：紧凑弹窗，「知道了」与标题同行，只滚正文；
 *   <li>其余（手机竖屏、窄分屏）：底部说明面板；
 *   <li>宽屏但无锚点（程序直接调用）：收紧的居中弹窗。
 * </ul>
 *
 * <p>只按窗口尺寸判定，不看字体缩放：大字体只让正文滚动，不改变形态。纯逻辑，不 import Android API。
 */
public final class InfoSurfacePolicy {
  private InfoSurfacePolicy() {}

  public static final int WIDE_MIN_WIDTH = 600;
  public static final int TALL_MIN_HEIGHT = 480;
  public static final int POPOVER_MAX_WIDTH = 360;
  public static final int POPOVER_MAX_HEIGHT = 420;
  public static final int POPOVER_EDGE = 12;
  public static final int POPOVER_GAP = 8;

  /** 小三角中心距气泡左右边缘的最小距离，避开圆角。 */
  public static final int CARET_INSET = 22;

  public enum Kind {
    POPOVER,
    COMPACT_DIALOG,
    SHEET,
    DIALOG
  }

  public static Kind kind(int widthDp, int heightDp, boolean hasAnchor) {
    if (heightDp < TALL_MIN_HEIGHT) return Kind.COMPACT_DIALOG;
    if (widthDp < WIDE_MIN_WIDTH) return Kind.SHEET;
    return hasAnchor ? Kind.POPOVER : Kind.DIALOG;
  }

  /** 卡片宽度；SHEET 为全宽。 */
  public static int width(Kind kind, int widthDp) {
    int w = Math.max(0, widthDp);
    switch (kind) {
      case POPOVER:
        return Math.max(0, Math.min(POPOVER_MAX_WIDTH, w - 2 * POPOVER_EDGE));
      case COMPACT_DIALOG:
        return Math.max(0, Math.min(560, w - 48));
      case SHEET:
        return w;
      default:
        int margin = w >= WIDE_MIN_WIDTH ? 24 : 16;
        return Math.max(0, Math.min(w >= WIDE_MIN_WIDTH ? 440 : 400, w - 2 * margin));
    }
  }

  /** 卡片最大高度；超出时标题与关闭入口固定，正文滚动。气泡另受锚点一侧可用高度限制。 */
  public static int maxHeight(Kind kind, int heightDp) {
    int h = Math.max(0, heightDp);
    switch (kind) {
      case POPOVER:
        return Math.min(POPOVER_MAX_HEIGHT, Math.max(0, h - 2 * POPOVER_EDGE));
      case COMPACT_DIALOG:
        return Math.max(0, h - 32);
      case SHEET:
        return Math.round(h * 0.6f);
      default:
        return Math.max(0, h - 48);
    }
  }

  /** 气泡相对容器的落点；caretX 为小三角中心相对气泡左边缘的位置。 */
  public static final class Placement {
    public final int left, top, maxHeight, caretX;
    public final boolean below;

    Placement(int left, int top, int maxHeight, int caretX, boolean below) {
      this.left = left;
      this.top = top;
      this.maxHeight = maxHeight;
      this.caretX = caretX;
      this.below = below;
    }
  }

  /**
   * 气泡放在锚点下方 GAP 处；下方放不下自然高度且上方更宽裕时翻到上方。水平以锚点中心对齐，夹在容器左右 EDGE
   * 内；小三角始终指向锚点。
   *
   * @param containerW 容器宽（已扣除系统栏）
   * @param containerH 容器高
   * @param anchorLeft 锚点（ⓘ 图标）在容器内的左边
   * @param naturalHeight 气泡不受限时的高度
   */
  public static Placement place(
      int containerW,
      int containerH,
      int anchorLeft,
      int anchorTop,
      int anchorRight,
      int anchorBottom,
      int popoverWidth,
      int naturalHeight) {
    int spaceBelow = containerH - POPOVER_EDGE - (anchorBottom + POPOVER_GAP);
    int spaceAbove = anchorTop - POPOVER_GAP - POPOVER_EDGE;
    boolean below = naturalHeight <= spaceBelow || spaceBelow >= spaceAbove;
    int cap = Math.max(0, Math.min(POPOVER_MAX_HEIGHT, below ? spaceBelow : spaceAbove));
    int height = Math.min(naturalHeight, cap);
    int center = (anchorLeft + anchorRight) / 2;
    int maxLeft = Math.max(POPOVER_EDGE, containerW - POPOVER_EDGE - popoverWidth);
    int left = clamp(center - popoverWidth / 2, POPOVER_EDGE, maxLeft);
    int top = below ? anchorBottom + POPOVER_GAP : anchorTop - POPOVER_GAP - height;
    top = clamp(top, POPOVER_EDGE, Math.max(POPOVER_EDGE, containerH - POPOVER_EDGE - height));
    int caretX =
        clamp(center - left, CARET_INSET, Math.max(CARET_INSET, popoverWidth - CARET_INSET));
    return new Placement(left, top, cap, caretX, below);
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
