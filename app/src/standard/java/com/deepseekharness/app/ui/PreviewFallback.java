package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;

/** 标准版不携带额外浏览器内核。 */
final class PreviewFallback {
  static boolean preferred(Context context) {
    return false;
  }

  static boolean open(Activity activity, String url, String cookie) {
    return false;
  }
}
