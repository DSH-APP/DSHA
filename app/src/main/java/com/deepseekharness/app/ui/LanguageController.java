package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import java.util.Locale;

/**
 * 切换界面语言只重建 Activity；终端进程、Web 服务和业务任务保留。
 *
 * <p>语言来源有两条：用户显式选择（{@code zh}/{@code en}），或「跟随系统」（默认）。
 * 后者用 {@link com.deepseekharness.app.util.SystemLanguage} 解析，而不是
 * {@code Locale.getDefault()} —— 本类会把默认 Locale 改成界面语言，读它会让
 * 「跟随系统」在切换一次之后自我锁死。
 */
public final class LanguageController {
    private LanguageController() { }

    /** 应用语言并广播；{@code language} 必须是已经解析过的 zh/en。 */
    public static void apply(Context context) {
        String language = new ConfigStore(context).getUiLanguage();
        UiText.setLanguage(language);
        Locale locale = Locale.forLanguageTag(language);
        Locale.setDefault(locale);
        Context app = context.getApplicationContext();
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.setLocale(locale);
        // 通知、悬浮界面和后台任务使用 Application 资源；Activity 由 AppCompat 更新。
        app.getResources().updateConfiguration(configuration, app.getResources().getDisplayMetrics());
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
    }

    /**
     * 保存语言偏好并立即应用。
     *
     * <p>接受 {@code system}：此时按当前系统语言落地为 zh/en 生效，但保存的仍是
     * {@code system}，这样系统语言以后变了应用会跟着变。
     */
    public static void select(Context context, String preference) {
        if (!UiLanguagePreference.supported(preference)) return;
        ConfigStore config = new ConfigStore(context);
        if (preference.equals(config.getUiLanguagePreference())) return;
        config.setUiLanguage(preference);
        apply(context);
    }
}
