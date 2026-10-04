package com.deepseekharness.app.ui;

import android.content.Context;

final class RecoveryBrowserFactory {
  static RecoveryBrowserSurface create(Context context) {
    return new RecoveryWebSurface(context);
  }
}
