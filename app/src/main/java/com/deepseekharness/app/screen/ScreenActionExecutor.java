package com.deepseekharness.app.screen;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.capabilities.CapabilityResult;
import com.deepseekharness.app.util.AccessibilityTreeBudget;
import com.deepseekharness.app.util.ScreenNodeLeaseStore;
import com.deepseekharness.app.util.ScreenObservationLedger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** 动作只有一条派发路径；前置失败、系统拒绝、完成和结果未知分别返回。 */
public final class ScreenActionExecutor {
  private final ScreenScreenshotCapture captures;
  private final ScreenObservationLedger observations;
  private final ScreenNodeLeaseStore<ScreenSemanticNode> nodes;

  ScreenActionExecutor(
      ScreenScreenshotCapture captures,
      ScreenObservationLedger observations,
      ScreenNodeLeaseStore<ScreenSemanticNode> nodes) {
    this.captures = captures;
    this.observations = observations;
    this.nodes = nodes;
  }

  public JSONObject execute(ScreenRequestContext context, String operation, JSONObject args)
      throws Exception {
    context.validateCurrentTarget();
    if (operation.equals("tap") || operation.equals("swipe"))
      return coordinate(context, operation, args);
    if (operation.equals("key")) {
      int key =
          args.has("keycode")
              ? switch (args.optInt("keycode", -1)) {
                case 3 -> AccessibilityService.GLOBAL_ACTION_HOME;
                case 4 -> AccessibilityService.GLOBAL_ACTION_BACK;
                case 187 -> AccessibilityService.GLOBAL_ACTION_RECENTS;
                default -> -1;
              }
              : key(args.optString("name"));
      if (key < 0) return ScreenReply.refused("INVALID_KEY");
      if (!context.claimDispatch()) return ScreenReply.refused("SCREEN_RUN_CHANGED");
      return outcome(context, context.service().performGlobalAction(key));
    }
    try (var root = ScreenTargetResolver.resolve(context.service(), context.target().displayId)) {
      context.validateRoot(context.target().displayId, root.root);
      AccessibilityNodeInfo target = null;
      ScreenSemanticNode leased = null;
      try {
        int action;
        Bundle bundle = null;
        if (operation.equals("node")) {
          leased =
              nodes.consume(
                  args.optString("treeId"),
                  args.optString("nodeId"),
                  root.target,
                  context.serviceEpoch(),
                  context.ownerIdentity());
          if (leased == null) return ScreenReply.refused("STALE_NODE");
          target = leased.node;
          if (!leased.refreshMatches(root.root.getWindowId()))
            return ScreenReply.refused("STALE_NODE");
          action = nodeAction(args.optString("action"));
          if (action < 0) return ScreenReply.refused("INVALID_NODE_ACTION");
          if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) bundle = text(args);
        } else if (operation.equals("tapText")) {
          String query = args.optString("text");
          if (query.isEmpty() || query.length() > 2000) return ScreenReply.refused("INVALID_TEXT");
          target = find(root.root, query, 0, new AccessibilityTreeBudget());
          if (target == null) return ScreenReply.refused("NODE_NOT_FOUND");
          action = AccessibilityNodeInfo.ACTION_CLICK;
        } else if (operation.equals("input")) {
          target = root.root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
          if (target == null || !target.isEditable() || !target.isEnabled())
            return ScreenReply.refused("INPUT_NOT_FOCUSED");
          action = AccessibilityNodeInfo.ACTION_SET_TEXT;
          bundle = text(args);
        } else return ScreenReply.refused("UNKNOWN_SCREEN_OPERATION");
        if (leased != null
            ? !leased.refreshMatches(root.root.getWindowId())
            : !target.refresh() || target.getWindowId() != root.root.getWindowId())
          return ScreenReply.refused("STALE_NODE");
        context.validateCurrentTarget();
        if (operation.equals("input")) {
          try (var latest =
              ScreenTargetResolver.resolve(context.service(), context.target().displayId)) {
            context.validateRoot(context.target().displayId, latest.root);
            AccessibilityNodeInfo focused =
                latest.root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            try {
              if (focused == null || !focused.equals(target))
                return ScreenReply.refused("EDITOR_CHANGED");
            } finally {
              if (focused != null) focused.recycle();
            }
          }
        }
        if (!context.claimDispatch()) return ScreenReply.refused("SCREEN_RUN_CHANGED");
        return semanticOutcome(context, target.performAction(action, bundle), action);
      } finally {
        if (leased != null) leased.close();
        else if (target != null) target.recycle();
      }
    }
  }

  private JSONObject coordinate(ScreenRequestContext context, String operation, JSONObject args)
      throws Exception {
    if (android.os.Build.VERSION.SDK_INT < 24) return ScreenReply.refused("GESTURE_UNSUPPORTED");
    return coordinateSupported(context, operation, args);
  }

  @androidx.annotation.RequiresApi(24)
  private JSONObject coordinateSupported(
      ScreenRequestContext context, String operation, JSONObject args) throws Exception {
    ScreenObservationLedger.Observation seen =
        observations.consume(
            args.optString("observationId"),
            context.target(),
            context.serviceEpoch(),
            context.ownerIdentity());
    if (seen == null)
      return frameRefusal("FRAME_NOT_OBSERVED", "SCREENSHOT_OBSERVATION_ID_REQUIRED_OR_EXPIRED");
    boolean tap = operation.equals("tap");
    String gesture = tap ? args.optString("gesture", "tap") : "swipe";
    if (tap && !java.util.Set.of("tap", "long_press", "double_tap").contains(gesture))
      return ScreenReply.refused("INVALID_GESTURE");
    int x1 = args.optInt(tap ? "x" : "x1", -1), y1 = args.optInt(tap ? "y" : "y1", -1);
    int x2 = tap ? x1 : args.optInt("x2", -1), y2 = tap ? y1 : args.optInt("y2", -1);
    int millis =
        gesture.equals("long_press") ? args.optInt("ms", 600) : tap ? 50 : args.optInt("ms", 300);
    if (seen.region.obscured())
      return frameRefusal("OBSERVED_TARGET_OCCLUDED", "ANOTHER_WINDOW_COVERS_TARGET");
    if (!seen.region.containsTrajectory(x1, y1, x2, y2, 1)
        || millis < (gesture.equals("long_press") ? 500 : 50)
        || millis > 5000)
      return frameRefusal(
          "GESTURE_OUTSIDE_OBSERVED_REGION", "PATH_OR_BOUNDARY_RESERVE_OUTSIDE_TARGET");
    // 只比较已返回给调用方的画面。此处截图不发布observationId，不能替代用户已观察。
    try (var current = captures.capture(context)) {
      if (!seen.region.equals(current.region))
        return frameRefusal("STALE_FRAME", "TARGET_WINDOW_GEOMETRY_OR_OCCLUSION_CHANGED");
      if (!seen.matches(
          current.bitmap.getWidth(),
          current.bitmap.getHeight(),
          current.rotation,
          current.fingerprint,
          current.region)) return frameRefusal("STALE_FRAME", "OBSERVED_REGION_PIXELS_CHANGED");
    }
    return Api24.dispatch(this, context, seen, x1, y1, x2, y2, millis, gesture);
  }

  @androidx.annotation.RequiresApi(24)
  private static final class Api24 {
    static JSONObject dispatch(
        ScreenActionExecutor owner,
        ScreenRequestContext context,
        ScreenObservationLedger.Observation seen,
        int x1,
        int y1,
        int x2,
        int y2,
        int millis,
        String kind)
        throws Exception {
      Path path = new Path();
      path.moveTo(x1, y1);
      if (kind.equals("swipe")) path.lineTo(x2, y2);
      GestureDescription.Builder builder =
          new GestureDescription.Builder()
              .addStroke(new GestureDescription.StrokeDescription(path, 0, millis));
      // Both taps form one explicit gesture, admitted once against one observed target.
      if (kind.equals("double_tap"))
        builder.addStroke(new GestureDescription.StrokeDescription(path, 150, 50));
      GestureDescription gesture = builder.build();
      if (!seen.region.equals(context.currentRegion()))
        return frameRefusal("STALE_FRAME", "TARGET_WINDOW_GEOMETRY_OR_OCCLUSION_CHANGED");
      if (!context.claimDispatch()) return ScreenReply.refused("SCREEN_RUN_CHANGED");
      CountDownLatch done = new CountDownLatch(1);
      AtomicInteger state = new AtomicInteger();
      boolean accepted =
          context
              .service()
              .dispatchGesture(
                  gesture,
                  new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription description) {
                      state.set(1);
                      done.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription description) {
                      state.set(2);
                      done.countDown();
                    }
                  },
                  null);
      if (!accepted) return owner.outcome(context, false);
      long remaining =
          Math.min(6000L, context.request().deadlineElapsedRealtimeMillis - context.nowMillis());
      if (remaining <= 0 || !done.await(remaining, TimeUnit.MILLISECONDS))
        return ScreenReply.of(CapabilityResult.unknown("GESTURE_RESULT_UNKNOWN", ""))
            .put("effectVerified", false)
            .put("automaticRetry", false);
      if (state.get() != 1)
        return ScreenReply.of(CapabilityResult.partial("GESTURE_CANCELLED", ""))
            .put("effectVerified", false)
            .put("automaticRetry", false);
      return owner
          .outcome(context, true)
          .put("executionMechanism", "ACCESSIBILITY_TOUCH_GESTURE")
          .put("gesture", kind)
          .put("gestureCompleted", true)
          .put("effectVerified", false)
          .put("nextAction", "OBSERVE_SCREEN_TO_VERIFY_EFFECT")
          .put("automaticRetry", false);
    }
  }

  private JSONObject semanticOutcome(ScreenRequestContext context, boolean accepted, int action)
      throws Exception {
    JSONObject result = outcome(context, accepted);
    if (!accepted) return result;
    // Android's boolean acknowledges ACTION_CLICK/SET_TEXT, not a WebView DOM mutation.
    return result
        .put("code", "SCREEN_ACTION_SUBMITTED")
        .put("executionMechanism", "ACCESSIBILITY_NODE_ACTION")
        .put("accessibilityAction", action)
        .put("actionAccepted", true)
        .put("effectVerified", false)
        .put("nextAction", "OBSERVE_SCREEN_TO_VERIFY_EFFECT")
        .put("automaticRetry", false);
  }

  private JSONObject outcome(ScreenRequestContext context, boolean accepted) throws Exception {
    JSONObject result =
        accepted
            ? ScreenReply.completed()
            : ScreenReply.of(CapabilityResult.rejected("SCREEN_SYSTEM_REJECTED", ""));
    if (accepted) {
      if (!context.current()) result.put("runChangedAfterExecution", true);
      else {
        try {
          context.validateCurrentTarget();
        } catch (RuntimeException changed) {
          result.put("targetChangedAfterExecution", true);
        }
      }
    }
    return result;
  }

  private static JSONObject frameRefusal(String code, String reason) throws org.json.JSONException {
    return ScreenReply.refused(code)
        .put("reason", reason)
        .put("nextAction", "OBSERVE_A_NEW_SCREENSHOT_OR_USE_FRESH_TREE_NODE")
        .put("automaticRetry", false);
  }

  private static Bundle text(JSONObject args) {
    if (!args.has("text") || !(args.opt("text") instanceof String))
      throw new IllegalArgumentException("INVALID_TEXT");
    String text = args.optString("text");
    if (text.length() > 16000) throw new IllegalArgumentException("TEXT_LIMIT");
    Bundle bundle = new Bundle();
    bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
    return bundle;
  }

  private static int nodeAction(String action) {
    return switch (action) {
      case "click" -> AccessibilityNodeInfo.ACTION_CLICK;
      case "long_click" -> AccessibilityNodeInfo.ACTION_LONG_CLICK;
      case "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
      case "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
      case "focus" -> AccessibilityNodeInfo.ACTION_FOCUS;
      case "set_text" -> AccessibilityNodeInfo.ACTION_SET_TEXT;
      default -> -1;
    };
  }

  private static int key(String name) {
    return switch (name.toLowerCase(java.util.Locale.ROOT)) {
      case "back" -> AccessibilityService.GLOBAL_ACTION_BACK;
      case "home" -> AccessibilityService.GLOBAL_ACTION_HOME;
      case "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS;
      case "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
      case "quicksettings", "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
      case "lock" ->
          android.os.Build.VERSION.SDK_INT >= 28
              ? AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
              : -1;
      default -> -1;
    };
  }

  private static AccessibilityNodeInfo find(
      AccessibilityNodeInfo node, String text, int depth, AccessibilityTreeBudget budget) {
    if (node == null || !budget.enter(depth, 0) || node.isPassword()) return null;
    if (ScreenSnapshotReader.value(node.getText()).contains(text)
        || ScreenSnapshotReader.value(node.getContentDescription()).contains(text)) {
      AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
      for (int i = 0; i < 5 && current != null; i++) {
        if (current.isClickable() && current.isEnabled()) return current;
        AccessibilityNodeInfo parent = current.getParent();
        current.recycle();
        current = parent;
      }
      if (current != null) current.recycle();
    }
    for (int i = 0; i < node.getChildCount() && budget.childSlot(0); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      try {
        AccessibilityNodeInfo found = find(child, text, depth + 1, budget);
        if (found != null) return found;
      } finally {
        child.recycle();
      }
    }
    return null;
  }
}
