package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.util.Constants;

/** 旧系统 WebView 无法更新到所需版本时，使用随包内核。 */
final class PreviewFallback {
  static boolean preferred(Context context) {
    if (context
        .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        .getBoolean(Constants.KEY_GECKO_CORE, false)) return true;
    if (android.os.Build.VERSION.SDK_INT < 26) return true;
    // A Chrome user-agent version is not evidence that this page will fail. The
    // WebView path checks its actual required capabilities after page load and
    // opens Gecko on a missing capability. Prefer Gecko here only when the
    // installed WebView provider cannot be identified at all.
    try {
      return android.webkit.WebView.getCurrentWebViewPackage() == null;
    } catch (RuntimeException | LinkageError error) {
      return true;
    }
  }

  static boolean open(Activity activity, String url, String cookie) {
    try {
      activity.startActivity(
          new Intent(activity, GeckoPreviewActivity.class)
              .putExtra("url", url)
              .putExtra("cookie", cookie));
      activity.finish();
      return true;
    } catch (RuntimeException error) {
      return false;
    }
  }
}
