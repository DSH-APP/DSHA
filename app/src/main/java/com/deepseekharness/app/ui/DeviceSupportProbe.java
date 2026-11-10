package com.deepseekharness.app.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebView;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.util.DeviceSupportReport;

/** 本机只读采样：ABI / API / 厂商 / 系统 WebView。不联网、不上报。 */
final class DeviceSupportProbe {
  private DeviceSupportProbe() {}

  static DeviceSupportReport sample(Context context) {
    String pkg = "";
    String version = "";
    try {
      android.content.pm.PackageInfo web =
          Build.VERSION.SDK_INT >= 26 ? WebView.getCurrentWebViewPackage() : null;
      if (web != null) {
        pkg = web.packageName == null ? "" : web.packageName;
        version = web.versionName == null ? "" : web.versionName;
      }
    } catch (Exception | LinkageError ignored) {
    }
    return DeviceSupportReport.from(
        Build.VERSION.SDK_INT,
        Build.SUPPORTED_ABIS,
        Build.MANUFACTURER,
        Build.BRAND,
        Build.MODEL,
        Build.DISPLAY,
        pkg,
        version,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE);
  }

  static void copy(Context context, DeviceSupportReport report) {
    android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard == null) return;
    clipboard.setPrimaryClip(
        com.deepseekharness.app.bridge.SensitiveClipboard.text(
            com.deepseekharness.app.util.UiText.choose("本机环境", "Device environment"),
            report.copyText));
  }

  static boolean openWebViewSettings(Context context, DeviceSupportReport report) {
    if (Build.VERSION.SDK_INT >= 24) {
      try {
        context.startActivity(
            new Intent(android.provider.Settings.ACTION_WEBVIEW_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
      } catch (ActivityNotFoundException ignored) {
      }
    }
    String pkg = packageFromLabel(report.webViewLabel);
    if (pkg.isEmpty()) pkg = "com.google.android.webview";
    try {
      context.startActivity(
          new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
              .setData(Uri.parse("package:" + pkg))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      return true;
    } catch (ActivityNotFoundException ignored) {
      return false;
    }
  }

  private static String packageFromLabel(String label) {
    if (label == null) return "";
    String first = label.trim().split("\\s+")[0];
    return first.contains(".") ? first : "";
  }
}
