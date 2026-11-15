package com.deepseekharness.app.util;

/** 界面主题偏好的允许值；未知旧值回到跟随系统。 */
public final class UiThemePreference {
  public static final String SYSTEM = "system", LIGHT = "light", DARK = "dark";

  /** 主题页展示顺序：浅色、深色、跟随系统（与 Web 外观设置一致）。 */
  public static final String[] ORDER = {LIGHT, DARK, SYSTEM};

  private UiThemePreference() {}

  public static String normalize(String value) {
    return LIGHT.equals(value) || DARK.equals(value) ? value : SYSTEM;
  }

  public static boolean isDark(String value, boolean systemDark) {
    String mode = normalize(value);
    return DARK.equals(mode) || SYSTEM.equals(mode) && systemDark;
  }

  public static String next(String value) {
    String mode = normalize(value);
    return SYSTEM.equals(mode) ? LIGHT : LIGHT.equals(mode) ? DARK : SYSTEM;
  }

  /**
   * 从 {@code from} 切到 {@code to} 时界面明暗是否真的改变。
   *
   * <p>只有改变时 AppCompat 才会重建 Activity；不变时（例如系统本就是浅色，从“浅色”切到“跟随系统”）
   * 不能截图等待重建，否则遮罩无人接收。
   */
  public static boolean changesAppearance(String from, String to, boolean systemDark) {
    return isDark(from, systemDark) != isDark(to, systemDark);
  }
}
