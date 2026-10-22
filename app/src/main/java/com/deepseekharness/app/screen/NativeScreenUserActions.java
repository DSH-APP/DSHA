package com.deepseekharness.app.screen;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.capabilities.CapabilityResult;
import com.deepseekharness.app.util.ScreenConnectionEpoch;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** 原生页面明确用户输入；不创建或借用助手的运行代次/授权票据。 */
public final class NativeScreenUserActions {
  private NativeScreenUserActions() {}

  public static NativeScreenControlLease virtualControlLease(
      ScreenConnectionEpoch<DshaAccessibilityService> connection,
      com.deepseekharness.app.vscreen.VirtualScreenNativeControls subject) {
    return new NativeScreenControlLease(connection, subject);
  }

  public static JSONObject typeVirtualUser(
      ScreenConnectionEpoch<DshaAccessibilityService> connection,
      int displayId,
      String text,
      BooleanSupplier current) {
    var lease = connection.snapshot();
    if (lease == null) return ScreenReply.unavailable();
    if (lease == null || displayId <= 0 || current == null || text == null || text.length() > 16000)
      return ScreenReply.refused("VIRTUAL_INPUT_UNAVAILABLE");
    boolean submitted = false;
    AccessibilityNodeInfo input = null;
    try (var root = ScreenTargetResolver.resolve(lease.service, displayId)) {
      if (!current.getAsBoolean() || root.target == null || !connection.current(lease))
        return ScreenReply.refused("SCREEN_TARGET_CHANGED");
      input = root.root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
      if (input == null || !input.isEditable() || !input.isEnabled())
        return ScreenReply.refused("VIRTUAL_INPUT_NOT_FOCUSED");
      Bundle args = new Bundle();
      args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
      try (var latest = ScreenTargetResolver.resolve(lease.service, displayId)) {
        if (!root.target.matches(latest.target)
            || !input.refresh()
            || input.getWindowId() != latest.target.windowId
            || !current.getAsBoolean()
            || connection.claim(lease, () -> Boolean.TRUE) == null)
          return ScreenReply.refused("SCREEN_TARGET_CHANGED");
      }
      submitted = true;
      boolean accepted = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
      JSONObject result =
          accepted
              ? ScreenReply.completed()
              : ScreenReply.of(CapabilityResult.rejected("VIRTUAL_INPUT_REJECTED", ""));
      if (accepted && (!connection.current(lease) || !current.getAsBoolean()))
        result.put("runChangedAfterExecution", true);
      return result;
    } catch (Exception failure) {
      return ScreenReply.of(
          submitted
              ? CapabilityResult.unknown("VIRTUAL_INPUT_RESULT_UNKNOWN", "")
              : CapabilityResult.notSent("SCREEN_TARGET_CHANGED", ""));
    } finally {
      if (input != null) input.recycle();
    }
  }
}
