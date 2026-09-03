package com.deepseekharness.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 外观（配色主题）的选择与应用。
 *
 * <p>颜色引用已经从 {@code @color/xxx} 换成 {@code ?attr/dshaXxx}（见 res/values/attrs.xml），
 * 所以换外观就是换一套主题里的颜色槽，布局一行都不用动。
 *
 * <p>三套：
 * <ul>
 *   <li><b>default</b> —— 原来的蓝灰，跟随系统深浅（day/night 两份资源）；
 *   <li><b>sakura</b> —— 浅色暖粉，固定浅色；
 *   <li><b>glass</b> —— 深色玻璃：卡片半透明压在渐变底衬上，固定深色。
 * </ul>
 * 后两套不跟随系统深浅是刻意的：用户选了具体外观就是想要那个样子，
 * 再按系统夜间模式翻转一次只会得到两种他没选过的配色。
 *
 * <p>{@link #apply} 必须在 {@code super.onCreate()} 之前调用 —— 主题一旦开始解析
 * 窗口属性（背景、状态栏色）就晚了。
 */
final class DshaTheme {

    static final String KEY = "ui_theme";

    static final String DEFAULT = "default";
    static final String SAKURA = "sakura";
    static final String GLASS = "glass";

    /** 给设置界面用的顺序与名字。索引与 {@link #VALUES} 对应。 */
    static final String[] LABELS = {
            "默认 · 蓝灰（跟随系统深浅）",
            "樱花 · 浅色暖粉",
            "玻璃 · 深色半透明",
    };
    static final String[] VALUES = {DEFAULT, SAKURA, GLASS};

    private DshaTheme() {
    }

    static String current(Context ctx) {
        try {
            return prefs(ctx).getString(KEY, DEFAULT);
        } catch (Throwable t) {
            return DEFAULT;
        }
    }

    static int currentIndex(Context ctx) {
        String v = current(ctx);
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i].equals(v)) return i;
        }
        return 0;
    }

    /** 写入选择。调用方负责 {@code recreate()} —— 已经创建的窗口不会自己换主题。 */
    static void set(Context ctx, String value) {
        prefs(ctx).edit().putString(KEY, value).apply();
    }

    /** 在 Activity 的 onCreate 最开头调用（super 之前）。 */
    static void apply(Activity a) {
        try {
            String t = current(a);
            if (SAKURA.equals(t)) {
                a.setTheme(R.style.Theme_DeepseekHarness_Sakura);
            } else if (GLASS.equals(t)) {
                a.setTheme(R.style.Theme_DeepseekHarness_Glass);
            }
            // default 不用管：清单里的 android:theme 已经是 Theme.DeepseekHarness
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
    }
}
