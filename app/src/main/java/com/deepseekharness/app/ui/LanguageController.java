package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import java.util.Locale;

/** 切换界面语言只重建 Activity；终端进程、Web 服务和业务任务保留。 */
public final class LanguageController {
    private LanguageController() { }
    public static void apply(Context context) {
        String language=new ConfigStore(context).getUiLanguage();UiText.setLanguage(language);
        Locale locale=Locale.forLanguageTag(language);Locale.setDefault(locale);
        Context app=context.getApplicationContext();
        Configuration configuration=new Configuration(app.getResources().getConfiguration());
        configuration.setLocale(locale);
        // 通知、悬浮界面和后台任务使用 Application 资源；Activity 由 AppCompat 更新。
        app.getResources().updateConfiguration(configuration,app.getResources().getDisplayMetrics());
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
    }
    public static void select(Context context,String language) {
        if(!UiLanguagePreference.supported(language))return;
        ConfigStore config=new ConfigStore(context);
        if(language.equals(config.getUiLanguage()))return;
        config.setUiLanguage(language);apply(context);
    }
}
