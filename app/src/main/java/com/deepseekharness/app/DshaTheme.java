package com.deepseekharness.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配色主题的选择与应用。
 *
 * <p>颜色引用已经从 {@code @color/xxx} 换成 {@code ?attr/dshaXxx}（见 res/values/attrs.xml），
 * 所以换配色就是换一套主题里的颜色槽，布局一行都不用动。
 *
 * <p><b>配色与玻璃是两个维度</b>：这里只管配色（蓝灰 / 樱花），每套都有 day/night 两份资源、
 * 跟随系统深浅；玻璃是叠加在任意配色之上的开关，见 {@link DshaGlass#enabled}。
 * 早先把玻璃做成第三套主题是错的 —— 那样"樱花 + 玻璃"表达不出来，
 * 而且玻璃那套被钉死在深色上。
 *
 * <p>{@link #apply} 必须在 {@code super.onCreate()} 之前调用 —— 主题一旦开始解析
 * 窗口属性（背景、状态栏色）就晚了。
 */
final class DshaTheme {

    static final String KEY = "ui_theme";

    static final String DEFAULT = "default";
    static final String SAKURA = "sakura";
    /** 历史值。玻璃改成叠加开关之后，读到它就迁移。 */
    private static final String LEGACY_GLASS = "glass";

    static final String[] LABELS = {
            "默认 · 蓝灰",
            "樱花 · 暖粉",
    };
    static final String[] VALUES = {DEFAULT, SAKURA};

    private DshaTheme() {
    }

    static String current(Context ctx) {
        try {
            String v = prefs(ctx).getString(KEY, DEFAULT);
            if (LEGACY_GLASS.equals(v)) {
                // 之前选了"玻璃主题"的用户：配色回到默认，同时把玻璃开关打开，
                // 观感上最接近他原来选的那个。静默迁移，不弹提示。
                prefs(ctx).edit()
                        .putString(KEY, DEFAULT)
                        .putBoolean(DshaGlass.KEY_ENABLED, true)
                        .apply();
                return DEFAULT;
            }
            return v;
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
            if (SAKURA.equals(current(a))) {
                a.setTheme(R.style.Theme_DeepseekHarness_Sakura);
            }
            // 默认不用管：清单里的 android:theme 已经是 Theme.DeepseekHarness
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
    }
}
