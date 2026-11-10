package com.deepseekharness.app.util;

/** 纯展示策略：页面返回、进程结束和退出核验绝不互相替代。 */
public final class TerminalWorkspacePolicy {
  private static final int INLINE_MIN_WIDTH_DP = 840;
  private static final int TERMINAL_MIN_WIDTH_DP = 480;
  private static final int COMPACT_HEIGHT_DP = 560;
  private static final int DENSE_FOOTER_HEIGHT_DP = 480;
  private static final int LANDSCAPE_COMPACT_HEIGHT_DP = 1000;
  private static final int NARROW_WINDOW_MAX_WIDTH_DP = 600;
  private static final int DRAWER_MAX_WIDTH_DP = 336;
  private static final int NARROW_DRAWER_MAX_WIDTH_DP = 224;

  /** 窄于该宽度的侧栏（手机 224dp 抽屉及其更窄分屏）改用 dense 短标签动作组。 */
  private static final int NARROW_ACTIONS_MAX_WIDTH_DP = 240;

  private static final int INLINE_MIN_WIDTH = 240;
  private static final int INLINE_MAX_WIDTH = 296;

  private TerminalWorkspacePolicy() {}

  public enum Back {
    DISMISS_PANEL,
    LEAVE_SELECTION,
    HIDE_IME,
    LEAVE_PAGE
  }

  public static Back back(boolean panel, boolean selection, boolean ime) {
    if (panel) return Back.DISMISS_PANEL;
    if (selection) return Back.LEAVE_SELECTION;
    if (ime) return Back.HIDE_IME;
    return Back.LEAVE_PAGE;
  }

  public static boolean restoreSelection(
      String savedEpoch, String currentEpoch, long id, boolean exists) {
    return savedEpoch != null && savedEpoch.equals(currentEpoch) && id > 0 && exists;
  }

  /**
   * 宽屏是否具备内联侧栏的空间：除 840dp 断点外，终端仍须保留最小可读宽度。 极简模式只在侧栏被显式打开时使用该形态；工作台宽屏则常驻。
   */
  public static boolean canInline(int widthDp) {
    return widthDp >= INLINE_MIN_WIDTH_DP
        && widthDp - inlineWidth(widthDp) >= TERMINAL_MIN_WIDTH_DP;
  }

  /** 内联侧栏宽度：约四分之一窗口，240–296dp，避免平板上的手机窄栏。 */
  public static int inlineWidth(int widthDp) {
    return Math.max(INLINE_MIN_WIDTH, Math.min(INLINE_MAX_WIDTH, Math.round(widthDp * .25f)));
  }

  /**
   * 抽屉最大宽度按设备类与当前窗口宽度双重判定：手机类（{@code smallestScreenWidthDp < 600}，与附加键布局同一判据）
   * 且当前窗口 &lt; 600dp 时收窄到 224dp；平板（含分屏窄窗）、手机宽窗与宽屏保持 336dp。
   * 内联侧栏不受该上限影响，仍由 {@link #inlineWidth(int)} 决定。
   */
  public static int drawerMaxWidth(int widthDp, int smallestWidthDp) {
    boolean phone = smallestWidthDp < NARROW_WINDOW_MAX_WIDTH_DP;
    return phone && widthDp < NARROW_WINDOW_MAX_WIDTH_DP
        ? NARROW_DRAWER_MAX_WIDTH_DP
        : DRAWER_MAX_WIDTH_DP;
  }

  /**
   * 抽屉保留遮罩区；极窄窗口最多让出四分之一宽度，不能回退到溢出窗口的最大宽度。
   * maxPx 由 {@link #drawerMaxWidth(int, int)} 按设备类与当前窗口宽度给出。
   */
  public static int drawerWidth(int availablePx, int maxPx, int marginPx) {
    if (availablePx <= 0) return maxPx;
    int margin = Math.min(Math.max(0, marginPx), availablePx / 4);
    return Math.min(maxPx, Math.max(1, availablePx - margin));
  }

  /** 短窗口隐藏说明文字并收紧内边距，优先保证会话与底部动作可达。 */
  public static boolean compactHeight(int heightDp) {
    return compactHeight(heightDp, 1f);
  }

  public static boolean compactHeight(int heightDp, float fontScale) {
    return heightDp < COMPACT_HEIGHT_DP * readableScale(fontScale);
  }

  /** 短窗优先减少动作行数；实际列数仍须满足侧栏宽度和字体大小。 */
  public static boolean denseFooter(int heightDp) {
    return denseFooter(heightDp, 1f);
  }

  public static boolean denseFooter(int heightDp, float fontScale) {
    return heightDp < DENSE_FOOTER_HEIGHT_DP * readableScale(fontScale);
  }

  /**
   * 窄侧栏是否改用 dense 短标签动作组（2 列 3 行、单行等高 48dp）。
   *
   * <p>224dp 抽屉放不下全标签：完整标签会折行，同一行按钮还会因行数不同而高度不齐。判据取
   * 240dp 而不是抽屉上限 224dp：内联侧栏最小就是 240dp，回到 240dp 即恢复全标签；与
   * {@link #footerColumns(int, float, boolean)} 在 224dp 给出的两列、{@link #drawerMaxWidth(int,
   * int)} 的手机类上限 224dp 保持一致，更窄的分屏窗口同样落在该分支。
   */
  public static boolean narrowActions(int panelWidthDp) {
    return panelWidthDp < NARROW_ACTIONS_MAX_WIDTH_DP;
  }

  /** 按侧栏自身宽度分列，不能用横屏窗口宽度代替每个按钮的实际空间。 */
  public static int footerColumns(int panelWidthDp, float fontScale, boolean compact) {
    int available = Math.max(0, panelWidthDp - 16);
    int minimum = (int) Math.ceil(96 * readableScale(fontScale)) + 6;
    return Math.max(1, Math.min(compact ? 3 : 2, available / minimum));
  }

  /** 窄栏或大字时把状态放到名称下方，给名称保留可读宽度。 */
  public static boolean stackSessionDetails(int panelWidthDp, float fontScale) {
    return panelWidthDp < 320 * readableScale(fontScale);
  }

  /**
   * 头部、新建和底部动作必须先按真实宽度测量；剩余高度不足两行会话时整栏滚动。
   * 传入值使用同一单位，不依赖设备屏幕或 IME 出现前的旧高度。
   */
  public static boolean scrollWholeSidebar(
      int availableHeight, int measuredChromeHeight, int minimumSessionHeight) {
    return availableHeight > 0
        && (long) availableHeight - Math.max(0, measuredChromeHeight)
            < Math.max(1, minimumSessionHeight);
  }

  public static int minimumSessionHeight(float fontScale) {
    return (int) Math.ceil(112 * readableScale(fontScale));
  }

  private static float readableScale(float fontScale) {
    return Float.isFinite(fontScale) && fontScale > 1f ? fontScale : 1f;
  }

  /**
   * 横屏/低高度工作台优先收紧说明和动作布局，列数仍按侧栏宽度与字体决定。
   * 1000dp 是体验阈值，不是屏幕类型判断。
   */
  public static boolean shortLandscape(int widthDp, int heightDp) {
    return widthDp > heightDp && heightDp < LANDSCAPE_COMPACT_HEIGHT_DP;
  }

  public static float fraction(float value) {
    return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : .6f;
  }

  public static int ballY(int height, int size, int margin, int reservedBottom, float fraction) {
    int low = Math.min(margin, Math.max(0, height - size));
    int high = Math.max(low, height - size - margin - Math.max(0, reservedBottom));
    return low + Math.round((high - low) * fraction(fraction));
  }

  public static int ballX(int width, int size, int margin, boolean right) {
    int low = Math.min(margin, Math.max(0, width - size));
    return right ? Math.max(low, width - size - margin) : low;
  }
}
