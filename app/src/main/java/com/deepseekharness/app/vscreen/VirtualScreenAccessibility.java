package com.deepseekharness.app.vscreen;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.util.AccessibilityDumpBounds;
import org.json.*;
import java.util.*;

/** 所有查询和动作均限定在虚拟 displayId，不使用主屏的 activeWindow。 */
public final class VirtualScreenAccessibility {
  public interface ObservedPixels {
    JSONObject capture(boolean retain) throws Exception;

    JSONObject region(String observationId, Rect bounds) throws Exception;

    void release(String observationId);

    java.util.concurrent.ScheduledFuture<?> expireAfter(Runnable cleanup, long millis);
  }

  private volatile VirtualScreenObservedControl observed;

  @androidx.annotation.RequiresApi(30)
  private synchronized VirtualScreenObservedControl observedOwner() {
    if (observed == null) observed = new VirtualScreenObservedControl();
    return observed;
  }

  public JSONObject observedControl(
      AccessibilityService service,
      int displayId,
      String operation,
      JSONObject args,
      com.deepseekharness.app.screen.ScreenRequestContext context,
      ObservedPixels pixels) {
    if (com.deepseekharness.app.BuildConfig.LOW_ANDROID) return fail("VSCREEN_UNSUPPORTED_LOW");
    if (android.os.Build.VERSION.SDK_INT < 30) return fail("VSCREEN_API_30_REQUIRED");
    return observedOwner()
        .run(
            service,
            displayId,
            operation,
            args,
            context,
            pixels,
            (node, root, request, submitted, leaseCurrent) -> {
              ActionGuard guard = new ActionGuard(request, displayId);
              guard.root = root;
              guard.leaseCurrent = leaseCurrent;
              return perform(node, submitted, guard, AccessibilityNodeInfo.ACTION_CLICK);
            });
  }

  void invalidateObserved() {
    if (android.os.Build.VERSION.SDK_INT >= 30 && observed != null) observed.invalidate();
  }

  void clearObserved() {
    if (android.os.Build.VERSION.SDK_INT >= 30 && observed != null) observed.clear();
  }

  private static final class ActionGuard {
    final com.deepseekharness.app.screen.ScreenRequestContext context;
    final com.deepseekharness.app.screen.NativeScreenControlLease.Action nativeAction;
    final int display;
    AccessibilityNodeInfo root;
    java.util.function.BooleanSupplier leaseCurrent;

    ActionGuard(com.deepseekharness.app.screen.ScreenRequestContext context, int display) {
      this(context, display, null);
    }

    ActionGuard(
        com.deepseekharness.app.screen.ScreenRequestContext context,
        int display,
        com.deepseekharness.app.screen.NativeScreenControlLease.Action nativeAction) {
      this.context = context;
      this.display = display;
      this.nativeAction = nativeAction;
    }

    void validate() {
      if (nativeAction != null) {
        nativeAction.validateRoot(display, root);
        nativeAction.validateCurrentTarget();
        if (!nativeAction.claimDispatch() || !nativeAction.current())
          throw new com.deepseekharness.app.DshaAccessibilityService.TargetChangedException();
        return;
      }
      if (leaseCurrent != null && !leaseCurrent.getAsBoolean())
        throw new com.deepseekharness.app.DshaAccessibilityService.TargetChangedException();
      if (context != null) {
        context.validateRoot(display, root);
        if (leaseCurrent != null && !leaseCurrent.getAsBoolean())
          throw new com.deepseekharness.app.DshaAccessibilityService.TargetChangedException();
        if (!context.claimDispatch())
          throw new com.deepseekharness.app.DshaAccessibilityService.TargetChangedException();
        if (leaseCurrent != null && !leaseCurrent.getAsBoolean())
          throw new com.deepseekharness.app.DshaAccessibilityService.TargetChangedException();
      }
    }
  }

  private final Map<String, AccessibilityNodeInfo> nodes = new LinkedHashMap<>();
  private int treeDisplay = -1;
  private long treeAt;
  private com.deepseekharness.app.util.ScreenTarget treeTarget;

  VirtualScreenAccessibility() {}

  /** Only an explicit native owner can enter this path; remote null contexts remain rejected. */
  synchronized JSONObject runNative(
      com.deepseekharness.app.screen.NativeScreenControlLease lease,
      int displayId,
      String operation,
      JSONObject args) {
    if (!operation.equals("tree") && !operation.equals("node"))
      return fail("INVALID_NATIVE_NODE_OPERATION");
    if (lease == null || lease.service() == null)
      return com.deepseekharness.app.screen.ScreenReply.unavailable();
    boolean tree = operation.equals("tree");
    if (tree) clear();
    var action = lease.begin(displayId, tree ? null : treeTarget, tree);
    if (action == null)
      return com.deepseekharness.app.screen.ScreenReply.refused("NATIVE_CONTROL_REVOKED");
    try (action) {
      JSONObject value = run(lease.service(), displayId, operation, args, null, action);
      if (!action.current()) {
        clear();
        return nativeFailure(action, !tree, "SCREEN_TARGET_CHANGED");
      }
      if (value.optBoolean("ok")) {
        action.validateCurrentTarget();
        if (tree) {
          JSONArray rows = value.optJSONArray("nodes");
          if (rows != null)
            for (int i = 0; i < rows.length(); i++) {
              JSONObject row = rows.getJSONObject(i);
              AccessibilityNodeInfo node = nodes.get(row.optString("nodeId"));
              if (node != null)
                row.put("enabled", node.isEnabled())
                    .put("password", node.isPassword())
                    .put(
                        "textTruncated",
                        !node.isPassword()
                            && node.getText() != null
                            && node.getText().length() > 1000)
                    .put("setTextSupported", nativeTextSupported(node));
            }
          value.put("treeTtlMs", 30_000);
          if (treeTarget != null)
            value.put("packageName", treeTarget.packageName).put("windowId", treeTarget.windowId);
        }
      }
      return value;
    } catch (Throwable changed) {
      clear();
      return nativeFailure(action, !tree, "SCREEN_TARGET_CHANGED");
    }
  }

  private static boolean nativeTextSupported(AccessibilityNodeInfo node) {
    return node.isEnabled()
        && node.isEditable()
        && !node.isPassword()
        && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
  }

  private static JSONObject nativeFailure(
      com.deepseekharness.app.screen.NativeScreenControlLease.Action action,
      boolean mutation,
      String code) {
    return com.deepseekharness.app.screen.ScreenReply.of(
        mutation && action.mayHaveSubmitted()
            ? com.deepseekharness.app.capabilities.CapabilityResult.unknown(
                "VIRTUAL_INPUT_RESULT_UNKNOWN", "")
            : com.deepseekharness.app.capabilities.CapabilityResult.notSent(code, ""));
  }

  public synchronized JSONObject run(
      AccessibilityService service, int displayId, String operation, JSONObject args) {
    return run(service, displayId, operation, args, null);
  }

  public synchronized JSONObject run(
      AccessibilityService service,
      int displayId,
      String operation,
      JSONObject args,
      com.deepseekharness.app.screen.ScreenRequestContext context) {
    return run(service, displayId, operation, args, context, null);
  }

  private JSONObject run(
      AccessibilityService service,
      int displayId,
      String operation,
      JSONObject args,
      com.deepseekharness.app.screen.ScreenRequestContext context,
      com.deepseekharness.app.screen.NativeScreenControlLease.Action nativeAction) {
    ActionGuard guard = new ActionGuard(context, displayId, nativeAction);
    AccessibilityNodeInfo root = null, target = null;
    boolean[] submitted = {false};
    try {
      if (operation.equals("tree")) clear();
      if (service == null) return fail("ACCESSIBILITY_UNAVAILABLE");
      if (android.os.Build.VERSION.SDK_INT < 30 || displayId <= 0) return fail("INVALID_DISPLAY");
      root = com.deepseekharness.app.DshaAccessibilityService.virtualRoot(displayId);
      if (root == null) {
        clear();
        return fail("VIRTUAL_WINDOW_UNAVAILABLE")
            .put(
                "treeUnavailable",
                com.deepseekharness.app.DshaAccessibilityService.windowDiagnostic());
      }
      if (nativeAction != null) nativeAction.validateRoot(displayId, root);
      else if (context == null)
        com.deepseekharness.app.DshaAccessibilityService.validateActionRoot(displayId, root);
      else context.validateRoot(displayId, root);
      guard.root = root;
      // Read-only text is sensitive too: the approved request is one-use before traversal.
      if (operation.equals("tree") || operation.equals("editor")) guard.validate();
      if (operation.equals("tree")) {
        JSONArray tree = new JSONArray();
        var budget = new com.deepseekharness.app.util.AccessibilityTreeBudget();
        walk(root, tree, 0, budget);
        var identity =
            new com.deepseekharness.app.util.ScreenTarget(
                displayId,
                root.getWindowId(),
                root.getPackageName() == null ? "" : root.getPackageName().toString());
        if (tree.length() == 0) {
          // A single read-only refresh, still bound to the same display/window/package.
          var refreshed =
              com.deepseekharness.app.DshaAccessibilityService.refreshVirtualRoot(displayId, root);
          root.recycle();
          root = refreshed;
          guard.root = root;
          if (context != null && root != null) context.validateRoot(displayId, root);
          if (nativeAction != null && root != null) nativeAction.validateRoot(displayId, root);
          if (root != null) {
            budget = new com.deepseekharness.app.util.AccessibilityTreeBudget();
            walk(root, tree, 0, budget);
          }
        }
        if (root == null) {
          clear();
          return fail("VIRTUAL_WINDOW_UNAVAILABLE")
              .put("nodes", tree)
              .put("treeUnavailable", "ROOT_REFRESH_UNAVAILABLE");
        }
        if (!com.deepseekharness.app.DshaAccessibilityService.matchesTarget(identity))
          throw new com.deepseekharness.app.DshaAccessibilityService.TargetChangedException();
        JSONObject result = ok().put("nodes", tree).put("visited", budget.visits());
        if (budget.exhausted()) result.put("truncated", true);
        if (tree.length() == 0) {
          clear();
          result.put(
              "treeUnavailable",
              root == null ? "ROOT_REFRESH_UNAVAILABLE" : "EMPTY_ACCESSIBILITY_TREE");
        } else {
          treeDisplay = displayId;
          treeTarget = identity;
          treeAt = android.os.SystemClock.elapsedRealtime();
        }
        return result;
      }
      if (operation.equals("node")) {
        var current =
            new com.deepseekharness.app.util.ScreenTarget(
                displayId,
                root.getWindowId(),
                root.getPackageName() == null ? "" : root.getPackageName().toString());
        if (displayId != treeDisplay
            || treeTarget == null
            || !treeTarget.matches(current)
            || android.os.SystemClock.elapsedRealtime() - treeAt > 30000) {
          clear();
          return fail("STALE_TREE");
        }
        target = nodes.remove(args.optString("nodeId"));
        if (target == null || !target.refresh() || target.getWindowId() != root.getWindowId())
          return fail("STALE_NODE");
        String action = args.optString("action");
        if (nativeAction != null
            && (!target.isEnabled()
                || action.equals("click") && !target.isClickable()
                || action.equals("set_text") && !nativeTextSupported(target)))
          return fail("NATIVE_NODE_ACTION_UNAVAILABLE");
        if (nativeAction != null && !action.equals("click") && !action.equals("set_text"))
          return fail("INVALID_NATIVE_NODE_ACTION");
        boolean result;
        switch (action) {
          case "click":
            result = perform(target, submitted, guard, AccessibilityNodeInfo.ACTION_CLICK);
            break;
          case "long_click":
            result = perform(target, submitted, guard, AccessibilityNodeInfo.ACTION_LONG_CLICK);
            break;
          case "scroll_forward":
            result = perform(target, submitted, guard, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            break;
          case "scroll_backward":
            result =
                perform(target, submitted, guard, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            break;
          case "focus":
            result = perform(target, submitted, guard, AccessibilityNodeInfo.ACTION_FOCUS);
            break;
          case "set_text":
            Bundle b = new Bundle();
            String value = args.optString("text");
            if (value.length() > 16000) return fail("TEXT_LIMIT");
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            result = perform(target, submitted, guard, AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            break;
          default:
            return fail("INVALID_NODE_ACTION");
        }
        return result ? ok() : fail("NODE_ACTION_REJECTED");
      }
      target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
      if (target == null || !target.isEditable()) return fail("VIRTUAL_INPUT_NOT_FOCUSED");
      if (target.isPassword()) return fail("PASSWORD_INPUT_UNAVAILABLE");
      String id = target.getWindowId() + ":" + target.hashCode();
      if (operation.equals("editor"))
        return ok().put("editorId", id)
            .put("text", target.getText() == null ? "" : target.getText().toString())
            .put("start", target.getTextSelectionStart())
            .put("end", target.getTextSelectionEnd())
            .put("inputType", target.getInputType())
            .put("multiline", target.isMultiLine());
      if (!id.equals(args.optString("editorId"))) return fail("EDITOR_CHANGED");
      if (operation.equals("submit"))
        return perform(
                target,
                submitted,
                guard,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())
            ? ok()
            : fail("IME_ACTION_UNAVAILABLE");
      if (!operation.equals("edit")) return fail("INVALID_EDITOR_OPERATION");
      String text = args.optString("text");
      int start = args.optInt("start", text.length()), end = args.optInt("end", start);
      if (text.length() > 16000
          || start < 0
          || end < 0
          || start > text.length()
          || end > text.length()) return fail("INVALID_EDITOR_RANGE");
      Bundle b = new Bundle();
      b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
      if (!perform(target, submitted, guard, AccessibilityNodeInfo.ACTION_SET_TEXT, b))
        return fail("EDITOR_WRITE_REJECTED");
      b = new Bundle();
      b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
      b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
      boolean selected =
          perform(target, submitted, guard, AccessibilityNodeInfo.ACTION_SET_SELECTION, b);
      return ok().put("selectionApplied", selected);
    } catch (com.deepseekharness.app.DshaAccessibilityService.TargetChangedException rejected) {
      clear();
      if (nativeAction != null)
        return nativeFailure(nativeAction, operation.equals("node"), "SCREEN_TARGET_CHANGED");
      // 动作之前目标/运行状态就变了：动作没有落地，给调用方一个「未执行」的稳定码（#140）。
      if (submitted[0]) {
        JSONObject value = fail("VIRTUAL_INPUT_RESULT_UNKNOWN");
        try {
          value.put("executionUnknown", true).put("state", "PARTIAL");
        } catch (Exception ignored) {
        }
        return value;
      }
      return fail("SCREEN_TARGET_CHANGED");
    } catch (Throwable error) {
      clear();
      if (nativeAction != null)
        return nativeFailure(nativeAction, operation.equals("node"), "SCREEN_TARGET_CHANGED");
      JSONObject value = fail("ACCESSIBILITY_OPERATION_UNKNOWN");
      if (submitted[0])
        try {
          value.put("executionUnknown", true);
        } catch (Exception ignored) {
        }
      return value;
    } finally {
      if (target != null) target.recycle();
      if (root != null) root.recycle();
    }
  }

  private static boolean perform(
      AccessibilityNodeInfo target, boolean[] submitted, ActionGuard guard, int action) {
    return perform(target, submitted, guard, action, null);
  }

  private static boolean perform(
      AccessibilityNodeInfo target,
      boolean[] submitted,
      ActionGuard guard,
      int action,
      Bundle args) {
    guard.validate();
    submitted[0] = true;
    return target.performAction(action, args);
  }

  public synchronized void clear() {
    clearObserved();
    for (var node : nodes.values()) node.recycle();
    nodes.clear();
    treeDisplay = -1;
    treeTarget = null;
  }

  private void walk(
      AccessibilityNodeInfo node,
      JSONArray tree,
      int depth,
      com.deepseekharness.app.util.AccessibilityTreeBudget budget)
      throws JSONException {
    if (!budget.enter(depth, tree.length())) return;
    String text =
        node.isPassword() ? "" : String.valueOf(node.getText() == null ? "" : node.getText());
    String description =
        node.isPassword()
            ? ""
            : String.valueOf(
                node.getContentDescription() == null ? "" : node.getContentDescription());
    Rect rect = new Rect();
    node.getBoundsInScreen(rect);
    // 与主屏 dump 同一条不变量：反序矩形不是「空节点」，它的四条边都不能用来定位，
    // 报出去只会让调用方按一个错坐标点屏幕（issue #143）。
    if (AccessibilityDumpBounds.reportable(
        !text.isEmpty() || !description.isEmpty(),
        node.isClickable() || node.isEditable() || node.isScrollable(),
        rect.left,
        rect.top,
        rect.right,
        rect.bottom)) {
      String id = UUID.randomUUID().toString();
      nodes.put(id, AccessibilityNodeInfo.obtain(node));
      tree.put(
          new JSONObject()
              .put("nodeId", id)
              .put("text", text.substring(0, Math.min(text.length(), 1000)))
              .put("description", description.substring(0, Math.min(description.length(), 1000)))
              .put("clickable", node.isClickable())
              .put("editable", node.isEditable())
              .put("scrollable", node.isScrollable())
              .put(
                  "bounds",
                  new JSONArray(new int[] {rect.left, rect.top, rect.right, rect.bottom})));
    }
    for (int i = 0; i < node.getChildCount() && budget.childSlot(tree.length()); i++) {
      // Unreadable children also consume budget, so huge sparse child lists remain bounded.
      AccessibilityNodeInfo child = node.getChild(i);
      if (child != null)
        try {
          walk(child, tree, depth + 1, budget);
        } finally {
          child.recycle();
        }
    }
  }

  private static JSONObject ok() throws JSONException {
    return new JSONObject().put("ok", true);
  }

  private static JSONObject fail(String error) {
    try {
      return new JSONObject().put("ok", false).put("error", error);
    } catch (JSONException e) {
      return new JSONObject();
    }
  }
}
