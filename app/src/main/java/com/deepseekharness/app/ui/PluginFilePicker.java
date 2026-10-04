package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

/** 优先 Android DocumentsUI，避免第三方压缩软件抢占 OpenDocument 却不返回文件。 */
final class PluginFilePicker {
  private PluginFilePicker() {}

  static Intent intent(Context context, boolean alternative) {
    Intent picker =
        new Intent(alternative ? Intent.ACTION_GET_CONTENT : Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (!alternative) {
      for (ResolveInfo candidate : context.getPackageManager().queryIntentActivities(picker, 0)) {
        if (candidate.activityInfo == null
            || !candidate.activityInfo.exported
            || !candidate.activityInfo.enabled) continue;
        String pkg = candidate.activityInfo.packageName;
        if (pkg.equals("com.android.documentsui") || pkg.equals("com.google.android.documentsui")) {
          picker.setComponent(new android.content.ComponentName(pkg, candidate.activityInfo.name));
          break;
        }
      }
    }
    return picker;
  }
}
