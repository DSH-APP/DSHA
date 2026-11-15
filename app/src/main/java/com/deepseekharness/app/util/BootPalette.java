package com.deepseekharness.app.util;

/**
 * 开机加载页的配色。
 *
 * <p>直启 Web 时按最近一次 DSH 网页上报的背景色取前景，沿用 DSH 启动画面（dsh-web-frontend
 * {@code _boot} 样式）的浅色 / 深色两组标签与边框色，因此主题插件或外部主题壳改了网页背景后，
 * 下一次加载页仍与网页衔接。主界面路径直接使用宿主 shell 主题色，不经过本类。
 */
public final class BootPalette {
  /** DSH 启动画面浅色主题：--dsh-boot-label-primary / tertiary / border / brand。 */
  static final int LIGHT_PRIMARY = 0xFF0F1115;

  static final int LIGHT_TERTIARY = 0xFF81858C;
  static final int LIGHT_BORDER = 0x1A000000;

  /** DSH 启动画面深色主题（body[data-ds-dark-theme]）。 */
  static final int DARK_PRIMARY = 0xFFF9FAFB;

  static final int DARK_TERTIARY = 0xFFADB2B8;
  static final int DARK_BORDER = 0x1FFFFFFF;

  public final int background, primary, tertiary, border, brand;

  private BootPalette(int background, int primary, int tertiary, int border, int brand) {
    this.background = background;
    this.primary = primary;
    this.tertiary = tertiary;
    this.border = border;
    this.brand = brand;
  }

  public static BootPalette of(int background, int primary, int tertiary, int border, int brand) {
    return new BootPalette(background, primary, tertiary, border, brand);
  }

  /**
   * 由网页背景推导整套前景。半透明背景先按给定底色合成为不透明色，避免系统窗口透出。
   *
   * @param webBackground 网页上报的背景色（ARGB）
   * @param fallback 无法采用网页色时的宿主背景色（不透明）
   */
  public static BootPalette fromWeb(int webBackground, int fallback) {
    int opaque = composite(webBackground, fallback | 0xFF000000);
    boolean dark = dark(opaque);
    return new BootPalette(
        opaque,
        dark ? DARK_PRIMARY : LIGHT_PRIMARY,
        dark ? DARK_TERTIARY : LIGHT_TERTIARY,
        dark ? DARK_BORDER : LIGHT_BORDER,
        dark ? DARK_PRIMARY : LIGHT_PRIMARY);
  }

  /** WCAG 相对亮度低于 0.18 视为深色背景（与 DSH 深浅两组标签的对比分界一致）。 */
  public static boolean dark(int color) {
    return luminance(color) < 0.18;
  }

  static double luminance(int color) {
    return 0.2126 * linear((color >> 16) & 0xFF)
        + 0.7152 * linear((color >> 8) & 0xFF)
        + 0.0722 * linear(color & 0xFF);
  }

  private static double linear(int channel) {
    double value = channel / 255.0;
    return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }

  static int composite(int top, int bottom) {
    int alpha = (top >>> 24) & 0xFF;
    if (alpha == 0xFF) return top;
    int r = blend((top >> 16) & 0xFF, (bottom >> 16) & 0xFF, alpha);
    int g = blend((top >> 8) & 0xFF, (bottom >> 8) & 0xFF, alpha);
    int b = blend(top & 0xFF, bottom & 0xFF, alpha);
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  private static int blend(int top, int bottom, int alpha) {
    return (top * alpha + bottom * (255 - alpha) + 127) / 255;
  }
}
