package com.deepseekharness.app.util;

/** 应用只提供中英文；默认中文，不随系统语言改变已选择的界面语言。 */
public final class UiLanguagePreference {
    private UiLanguagePreference() { }
    public static String normalize(String value) { return "en".equals(value) ? "en" : "zh"; }
    public static boolean supported(String value) { return "zh".equals(value) || "en".equals(value); }
}
