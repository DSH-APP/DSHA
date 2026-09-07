package com.deepseekharness.app.util;

/** 界面主题偏好的允许值；未知旧值回到跟随系统。 */
public final class UiThemePreference {
    public static final String SYSTEM = "system", LIGHT = "light", DARK = "dark";
    private UiThemePreference() { }
    public static String normalize(String value) {
        return LIGHT.equals(value) || DARK.equals(value) ? value : SYSTEM;
    }
    public static boolean isDark(String value, boolean systemDark) {
        String mode = normalize(value);
        return DARK.equals(mode) || SYSTEM.equals(mode) && systemDark;
    }
}
