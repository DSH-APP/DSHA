package com.deepseekharness.app.ui;

import android.content.Context;
import com.deepseekharness.app.core.DiagnosticLog;
import com.deepseekharness.app.util.WebPageDiagnosticText;

/**
 * K0 证据：把 WebView 页面创建、销毁与 keeper 摘挂写成脱敏诊断行。
 *
 * <p>只记录原因码、代次、进程号与生命周期布尔事实；页面地址、cookie 与 token 不进入这里。
 */
final class WebPageDiagnostics {
  private WebPageDiagnostics() {}

  static void engine(Context context, long generation, String browserInfo) {
    DiagnosticLog.record(
        context,
        "WEB_ENGINE",
        WebPageDiagnosticText.engine(android.os.Process.myPid(), generation)
            + " webview="
            + String.valueOf(browserInfo));
  }

  static void pageDestroy(
      Context context, String reason, boolean changingConfig, boolean finishing, long generation) {
    DiagnosticLog.record(
        context,
        "WEB_PAGE_DESTROY",
        WebPageDiagnosticText.pageDestroy(reason, changingConfig, finishing, generation));
  }

  static void keepAlive(
      Context context, String action, String reason, long generation, boolean ready) {
    DiagnosticLog.record(
        context,
        "WEB_KEEPALIVE_" + action,
        WebPageDiagnosticText.keepAlive(action, reason, generation, ready));
  }

  /**
   * 前台恢复时客户端只读探针的结果（脱敏）：客户端、触发路径、Connection 状态/代次、探测结果与是否重置。
   *
   * <p>客户端未安装新探针时 {@code raw} 为 null，记录 probe=absent；地址/token 来自 JS，
   * 仍交给 {@link WebPageDiagnosticText} 的白名单过滤。
   */
  static void resume(
      Context context, String raw, boolean inApp, boolean ready, long pageGeneration) {
    String impl = "unknown", trigger = "unknown", visibility = "unknown", state = "unknown";
    String probe = "absent", stateAfter = "unknown";
    long elapsed = -1, generation = -1, generationAfter = -1, hidden = -1;
    boolean reset = false;
    if (raw != null && !raw.isEmpty() && !"null".equals(raw)) {
      try {
        org.json.JSONObject value = new org.json.JSONObject(raw);
        impl = value.optString("impl", "unknown");
        trigger = value.optString("trigger", "unknown");
        visibility = value.optString("visibility", "unknown");
        state = value.optString("state", "unknown");
        probe = value.optString("probe", "unknown");
        stateAfter = value.optString("stateAfter", "unknown");
        elapsed = value.optLong("elapsedMs", -1);
        generation = value.optLong("generation", -1);
        generationAfter = value.optLong("generationAfter", -1);
        hidden = value.optLong("hiddenMs", -1);
        reset = value.optBoolean("reset", false);
      } catch (org.json.JSONException ignored) {
      }
    }
    DiagnosticLog.record(
        context,
        "WEB_PAGE_RESUME",
        WebPageDiagnosticText.resume(
            impl,
            trigger,
            visibility,
            state,
            probe,
            elapsed,
            reset,
            stateAfter,
            generation,
            generationAfter,
            hidden,
            inApp,
            ready,
            pageGeneration));
  }
}
