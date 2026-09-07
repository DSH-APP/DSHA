package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatDelegate;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.UiThemePreference;

/** 统一保存和应用外观选择；Activity 重建由 AppCompat 处理。 */
public final class ThemeController {
    private ThemeController() { }
    public static void apply(Context context) {
        String mode = new ConfigStore(context).getUiTheme();
        int night = UiThemePreference.DARK.equals(mode) ? AppCompatDelegate.MODE_NIGHT_YES
                : UiThemePreference.LIGHT.equals(mode) ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(night);
    }
    public static void select(Context context, String mode) {
        new ConfigStore(context).setUiTheme(mode);
        apply(context);
    }
    public static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
    public static void toggle(Context context) {
        select(context, isDark(context) ? UiThemePreference.LIGHT : UiThemePreference.DARK);
    }
}
