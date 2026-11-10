package com.deepseekharness.app.util;

/**
 * 应用弹层（CardSheet / DshaDialogBuilder）的形态与几何判定，单位全部为 dp。
 *
 * <p>规则见 docs/design/ui-template-baseline-20261103/README.md §9。旧实现只看宽度（≥600 居中 540，
 * 否则贴底），手机横屏（约 780×316）拿到 540 宽的扁卡片，竖排页脚吃掉大半高度，正文只剩一百多 dp。 这里宽、高、内容档一起决定：
 *
 * <ul>
 *   <li>{@link Mode#BOTTOM}：宽 &lt; 600 且高 ≥ 480，贴底整宽；
 *   <li>{@link Mode#CENTER}：宽 ≥ 600 且高 ≥ 480，按内容档定宽的居中卡片；
 *   <li>{@link Mode#COMPACT}：高 &lt; 480 的短 / 中内容，收窄的居中卡片，页脚一行；
 *   <li>{@link Mode#SIDE}：高 &lt; 480、宽 ≥ 600 的长表单，左栏标题与操作，右栏正文滚动。
 * </ul>
 *
 * <p>高度阈值与 {@link InfoSurfacePolicy} 一致（480），两种弹层在同一窗口下给出同类形态。 只按窗口尺寸与调用方声明的内容档判定，不看字体缩放：大字体只让正文滚动。纯逻辑，不
 * import Android API。
 */
public final class SheetGeometry {
  private SheetGeometry() {}

  public static final int WIDE_MIN_WIDTH = 600;
  public static final int TALL_MIN_HEIGHT = InfoSurfacePolicy.TALL_MIN_HEIGHT;

  /** 贴底动作抽屉（BottomSheetCard）的首屏高度上限，约为可用高度的 0.72。 */
  public static final float BOTTOM_DRAWER_HEIGHT_RATIO = 0.72f;

  /** 宽 ≥ 840（设置双栏、layout-w840dp 同一判据）时长表单可再加宽。 */
  public static final int EXPANDED_MIN_WIDTH = 840;

  /** Material3 对话框窗口左右各有 24dp 背景内边距；窗口宽 = 可见宽 + 48。 */
  public static final int DIALOG_WINDOW_INSET = 24;

  /** 内容档由调用方声明：S 选择列表（≤4 项），M 说明 / 短列表，L 多段表单。 */
  public enum Size {
    S,
    M,
    L
  }

  public enum Mode {
    BOTTOM,
    CENTER,
    COMPACT,
    SIDE
  }

  public static Mode mode(int widthDp, int heightDp, Size size) {
    boolean tall = heightDp <= 0 || heightDp >= TALL_MIN_HEIGHT;
    if (tall) return widthDp < WIDE_MIN_WIDTH ? Mode.BOTTOM : Mode.CENTER;
    return size == Size.L && widthDp >= WIDE_MIN_WIDTH ? Mode.SIDE : Mode.COMPACT;
  }

  /** 卡片可见宽度；BOTTOM 为整宽。 */
  public static int width(Mode mode, Size size, int widthDp) {
    int w = Math.max(0, widthDp);
    switch (mode) {
      case BOTTOM:
        return w;
      case CENTER:
        int want =
            size == Size.S ? 400 : size == Size.L ? (w >= EXPANDED_MIN_WIDTH ? 720 : 640) : 480;
        return clamp(want, w - 48);
      case SIDE:
        return clamp(680, w - 48);
      default:
        return clamp(size == Size.M ? 480 : 400, w - 32);
    }
  }

  /**
   * 卡片最大高度。BOTTOM 扣掉抬高量（只有主界面要让出悬浮胶囊）；短屏两种形态只留 12dp 上下边距。
   *
   * @param liftDp 贴底弹层的抬高量，非主界面传 0
   */
  public static int maxHeight(Mode mode, int heightDp, int liftDp) {
    int h = Math.max(0, heightDp);
    switch (mode) {
      case BOTTOM:
        return Math.max(0, Math.round((h - Math.max(0, liftDp)) * 0.9f));
      case CENTER:
        return Math.max(0, Math.min(h - 80, 800));
      default:
        return Math.max(0, h - 24);
    }
  }

  /**
   * 贴底动作抽屉（BottomSheetCard）的首屏高度上限：约 0.72 × 可用高度（可用高度 = 屏幕高 − lift）。
   *
   * <p>与 {@link #maxHeight(Mode, int, int)} 的 BOTTOM 档（0.9，CardSheet 的通用贴底卡片）分开：动作抽屉
   * 是多行入口列表，内容很容易堆到 0.9 上限，把抽屉顶到屏幕上半区；首屏收矮后，超出部分在抽屉内滚动、
   * 页脚常驻。CardSheet 的贴底说明 / 选择 / 长表单仍用 0.9（调用面广，长表单也需要更多正文高度），不在
   * 本次动作抽屉修复里一并收紧。短屏（高度 &lt; 480dp）不走这里，由调用方保持整屏可滚动。
   *
   * @param liftDp 贴底弹层的抬高量，非主界面传 0
   */
  public static int bottomDrawerMaxHeight(int heightDp, int liftDp) {
    return Math.max(
        0, Math.round((Math.max(0, heightDp) - Math.max(0, liftDp)) * BOTTOM_DRAWER_HEIGHT_RATIO));
  }

  /** SIDE 左栏（标题、说明与竖排操作）宽度，约占卡片 36%。 */
  public static int sideColumnWidth(int cardWidthDp) {
    return Math.round(Math.max(0, cardWidthDp) * 0.36f);
  }

  /**
   * 页脚是否横排。只处理两个按钮：三个及以上在 400dp 宽内会截断文案，保持竖排； SIDE 的操作在左栏底部，始终竖排。
   */
  public static boolean footerInRow(Mode mode, int buttons) {
    return mode != Mode.SIDE && buttons == 2;
  }

  /**
   * AlertDialog 窗口宽度（含 Material 内边距）。只有标题、正文与按钮的简单对话框收窄到可见 400dp； 自定义内容（语言网格、应用选择等）保留原来的 560dp 上限，避免挤坏布局。
   */
  public static int dialogWindowWidth(int widthDp, boolean customView) {
    int visible = customView ? 560 - 2 * DIALOG_WINDOW_INSET : 400;
    return Math.max(0, Math.min(visible + 2 * DIALOG_WINDOW_INSET, Math.max(0, widthDp)));
  }

  private static int clamp(int want, int limit) {
    return Math.max(0, Math.min(want, limit));
  }
}
