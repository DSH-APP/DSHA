package com.deepseekharness.app.util;

/**
 * 调色板注册表：与日 / 夜偏好正交（每个调色板都同时有日夜两套颜色）。
 *
 * <p>颜色由 tools/generate-theme-palettes.py 生成：强调色与几层底色经 {@code ?attr/dsha_pal_*} 引用，
 * 界面层按 id 叠加 {@code ThemeOverlay.DSHA.Palette.<Id>}。新增调色板须同时改生成器与这里的列表。未知或
 * 已下线的值一律回到标准色，不让旧偏好把界面卡在不存在的主题上。
 */
public final class UiThemePalette {
  public static final String STANDARD = "standard",
      AURORA = "aurora",
      SAKURA = "sakura",
      CYBER = "cyber",
      AMBER = "amber";

  /** 已注册的调色板，按主题页展示顺序；与生成器 PALETTES 顺序一致。 */
  public static final String[] REGISTERED = {STANDARD, AURORA, SAKURA, CYBER, AMBER};

  private UiThemePalette() {}

  public static String normalize(String value) {
    for (String id : REGISTERED) if (id.equals(value)) return id;
    return STANDARD;
  }

  /** 只有一个调色板时主题页不展示选择器，避免出现只有一个选项的“选择”。 */
  public static boolean selectable() {
    return REGISTERED.length > 1;
  }

  /** 标准色就是主题默认值，不需要叠加层。 */
  public static boolean needsOverlay(String value) {
    return !STANDARD.equals(normalize(value));
  }

  /** 已创建页面的调色板与当前偏好不一致时需要重建。 */
  public static boolean stale(String applied, String current) {
    return !normalize(applied).equals(normalize(current));
  }
}
