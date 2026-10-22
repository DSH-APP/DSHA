package com.deepseekharness.app.screen;

import com.deepseekharness.app.capabilities.CapabilityResult;
import org.json.JSONObject;

/** 新内部结果保留桥的ok/error及未知执行字段，文案由旧路由适配。 */
public final class ScreenReply {
  private ScreenReply() {}

  public static JSONObject of(CapabilityResult result) {
    try {
      JSONObject value =
          new JSONObject()
              .put("ok", result.state == CapabilityResult.State.COMPLETED)
              .put("state", result.state.name())
              .put("code", result.code);
      if (result.state != CapabilityResult.State.COMPLETED) value.put("error", result.code);
      if (result.state == CapabilityResult.State.UNKNOWN) value.put("executionUnknown", true);
      if (result.state == CapabilityResult.State.PARTIAL) value.put("partial", true);
      if (!result.detail.isEmpty()) value.put("detail", result.detail);
      return value;
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static JSONObject refused(String code) {
    return of(CapabilityResult.notSent(code, ""));
  }

  public static JSONObject cancelled(String code, boolean mayHaveSubmitted, boolean pending) {
    try {
      return of(mayHaveSubmitted
              ? CapabilityResult.unknown(code, "")
              : CapabilityResult.notSent(code, ""))
          .put("automaticRetry", false)
          .put("underlyingRequestMayStillBeRunning", pending);
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static JSONObject completed() {
    return of(CapabilityResult.completed("SCREEN_COMPLETED", ""));
  }

  public static JSONObject unavailable() {
    try {
      return refused("ACCESSIBILITY_UNAVAILABLE")
          .put("availability", "UNAVAILABLE")
          .put("nextAction", "OPEN_ACCESSIBILITY_SETTINGS")
          .put("settingsAction", "android.settings.ACCESSIBILITY_SETTINGS");
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static JSONObject failure(ScreenRequestContext context, String code) {
    return of(
        context != null && context.submitted()
            ? CapabilityResult.unknown(code, "")
            : CapabilityResult.notSent(code, ""));
  }
}
