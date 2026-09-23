package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.webkit.WebSettings;
import com.deepseekharness.app.util.Constants;

/** 旧系统 WebView 无法更新到所需版本时，使用随包内核。 */
final class PreviewFallback {
    static boolean preferred(Context context) {
        if (context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_GECKO_CORE, false)) return true;
        if (android.os.Build.VERSION.SDK_INT < 26) return true;
        try {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("Chrome/(\\d+)")
                    .matcher(WebSettings.getDefaultUserAgent(context));
            return !match.find() || Integer.parseInt(match.group(1)) < 118;
        } catch (RuntimeException | LinkageError error) { return true; }
    }

    static boolean open(Activity activity, String url, String cookie) {
        try {
            // WebView 会刷新鉴权 URL，fragment 导航意图必须独立保留到 Gecko。
            String requested=activity.getIntent().getStringExtra("url");
            boolean models=activity.getIntent().getBooleanExtra("dsha_open_models",false)
                    || requested!=null&&requested.endsWith("#dsha-models");
            activity.startActivity(new Intent(activity, GeckoPreviewActivity.class)
                    .putExtra("url", url).putExtra("cookie", cookie).putExtra("dsha_open_models",models));
            activity.finish();
            return true;
        } catch (RuntimeException error) { return false; }
    }
}
