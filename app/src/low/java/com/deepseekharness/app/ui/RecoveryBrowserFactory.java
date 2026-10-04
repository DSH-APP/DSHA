package com.deepseekharness.app.ui;

import android.content.Context;

final class RecoveryBrowserFactory {
  static RecoveryBrowserSurface create(Context context) {
    return PreviewFallback.preferred(context)
        ? new RecoveryGeckoSurface(context)
        : new RecoveryWebSurface(context);
  }
}
