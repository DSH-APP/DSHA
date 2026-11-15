package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.util.DiagnosticDigestText;
import java.util.ArrayList;
import java.util.List;

/**
 * 网页兜底卡片的诊断摘要：只读固定身份与既有脱敏事件，不依赖维护状态、不启动 guest、不走 SAF。
 *
 * <p>所有读取失败都降级为 {@code unavailable}，保证在环境维护、Web 未启动时也能给用户一份可复制的
 * 现场。
 */
public final class DiagnosticDigest {
  private static final String[] TAGS = {"WEB_JS", "WEB_SUBRESOURCE", "WEB_ROUTE_BLANK"};

  private DiagnosticDigest() {}

  public static String build(Context context) {
    Context app = context.getApplicationContext();
    String runtimeId = "unavailable";
    try {
      var installed = HarnessController.get(app).proot().installedRuntimeDescriptor();
      if (installed != null && installed.id() != null && !installed.id().isEmpty())
        runtimeId = installed.id();
    } catch (Exception | LinkageError unavailable) {
      // 维护或未装机时读不到：保留 unavailable，不推测。
    }
    String webView = "unavailable";
    try {
      android.content.pm.PackageInfo current = android.webkit.WebView.getCurrentWebViewPackage();
      if (current != null)
        webView = String.valueOf(current.packageName) + " " + String.valueOf(current.versionName);
    } catch (Exception | LinkageError unavailable) {
      // 与页面内核一致：读不到就不写。
    }
    long usable = 0;
    try {
      usable = app.getFilesDir().getUsableSpace();
    } catch (RuntimeException unavailable) {
      // 保持 0。
    }
    String maintenance = maintenance(app);
    return DiagnosticDigestText.build(
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
        runtimeId,
        webView,
        usable,
        maintenance,
        recentEvents(app));
  }

  private static String maintenance(Context app) {
    StringBuilder state = new StringBuilder();
    try {
      boolean pending = MaintenanceCoordinator.pending(app.getFilesDir());
      state.append(pending ? "pending=yes" : "pending=no");
      var snapshot = BackupTask.get(app).snapshot();
      if (snapshot != null) {
        state
            .append(" stage=")
            .append(
                snapshot.lastStage == null || snapshot.lastStage.isEmpty()
                    ? "-"
                    : fixed(snapshot.lastStage))
            .append(" status=")
            .append(snapshot.status == null ? "UNKNOWN" : snapshot.status.name())
            .append(" error=")
            .append(
                snapshot.detail == null || snapshot.detail.isEmpty()
                    ? "-"
                    : fixed(snapshot.detail));
      }
    } catch (RuntimeException unavailable) {
      state.append("unavailable");
    }
    return state.toString();
  }

  /** 只挑选网页诊断类别；其它业务阶段不进入摘要。 */
  static List<String> recentEvents(Context app) {
    List<String> selected = new ArrayList<>();
    try {
      for (String line : DiagnosticLog.readExportTail(app).split("\\r?\\n")) {
        for (String tag : TAGS)
          if (line.contains("[" + tag + "]")) {
            selected.add(line);
            break;
          }
        if (selected.size() > DiagnosticDigestText.MAX_EVENTS * 4) selected.remove(0);
      }
    } catch (Exception | LinkageError unavailable) {
      // 读取失败时保留空列表，摘要仍包含身份与维护状态。
    }
    return selected;
  }

  /** 默认错误码只保留大写/数字/下划线，避免自由文本混进摘要。 */
  private static String fixed(String value) {
    return value != null && value.matches("[A-Za-z0-9_.:-]{1,80}") ? value : "UNKNOWN";
  }
}
