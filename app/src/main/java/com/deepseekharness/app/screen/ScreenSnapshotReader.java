package com.deepseekharness.app.screen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.util.AccessibilityDumpBounds;
import com.deepseekharness.app.util.ScreenNodeLeaseStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** 按需生成主屏语义树；节点租约不充当像素观察令牌。 */
public final class ScreenSnapshotReader {
  public JSONObject read(
      ScreenRequestContext context, ScreenNodeLeaseStore<ScreenSemanticNode> leases)
      throws Exception {
    try (var resolved =
        ScreenTargetResolver.resolve(context.service(), context.target().displayId)) {
      context.validateRoot(context.target().displayId, resolved.root);
      String treeId =
          leases.begin(resolved.target, context.serviceEpoch(), context.ownerIdentity());
      JSONArray nodes = new JSONArray();
      StringBuilder text =
          new StringBuilder("窗口应用: ").append(resolved.target.packageName).append('\n');
      ScreenReadBudget budget = new ScreenReadBudget(context::nowMillis, context::current);
      walk(resolved.root, nodes, text, 0, budget, leases);
      context.validateCurrentTarget();
      JSONObject result =
          ScreenReply.completed()
              .put("kind", "dsha-screen-tree-v1")
              .put("text", text.toString())
              .put("treeId", treeId)
              .put("nodes", nodes)
              .put("serviceEpoch", context.serviceEpoch())
              .put("visited", budget.visits())
              .put("coordinateAuthorization", "SCREENSHOT_OBSERVATION_REQUIRED")
              .put("target", target(resolved.target));
      if (budget.exhausted()) result.put("truncated", true);
      if (budget.timedOut()) result.put("truncationReason", "EXPANSION_TIME_BUDGET");
      return result;
    } catch (Exception rejected) {
      leases.clear();
      throw rejected;
    }
  }

  static JSONObject target(com.deepseekharness.app.util.ScreenTarget target) throws Exception {
    return new JSONObject()
        .put("displayId", target.displayId)
        .put("windowId", target.windowId)
        .put("packageName", target.packageName);
  }

  private void walk(
      AccessibilityNodeInfo node,
      JSONArray nodes,
      StringBuilder text,
      int depth,
      ScreenReadBudget budget,
      ScreenNodeLeaseStore<ScreenSemanticNode> leases)
      throws Exception {
    if (node == null || !budget.enter(depth, nodes.length()) || text.length() > 12000) return;
    boolean password = node.isPassword();
    boolean clickable = node.isClickable();
    boolean editable = node.isEditable();
    boolean scrollable = node.isScrollable();
    String label = password ? "[PASSWORD]" : value(node.getText());
    String description = password ? "" : value(node.getContentDescription());
    if (label.isEmpty()) label = description;
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    ScreenNodeActions.ActionSnapshot actions = ScreenNodeActions.snapshot(node);
    if (AccessibilityDumpBounds.reportable(
        !label.isEmpty(),
        clickable || editable || scrollable || actions.hasAny(),
        bounds.left,
        bounds.top,
        bounds.right,
        bounds.bottom)) {
      JSONArray supportedActions = actions.names();
      boolean enabled = node.isEnabled();
      boolean checked = node.isChecked();
      String id = leases.add(new ScreenSemanticNode(node, actions.mask()));
      nodes.put(
          new JSONObject()
              .put("nodeId", id)
              .put("text", label)
              .put("description", description)
              .put("clickable", clickable)
              .put("editable", editable)
              .put("scrollable", scrollable)
              .put("supportedActions", supportedActions)
              .put("enabled", enabled)
              .put("checked", checked)
              .put("boundsHaveArea", bounds.width() > 0 && bounds.height() > 0)
              .put(
                  "bounds",
                  new JSONArray(new int[] {bounds.left, bounds.top, bounds.right, bounds.bottom})));
      text.append('[')
          .append(nodes.length())
          .append("] \"")
          .append(label.replace('\n', ' '))
          .append('"');
      if (clickable) text.append(" 可点击");
      if (editable) text.append(" 可输入");
      if (actions.supports(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        text.append(" 可向前滚动(scroll_forward)");
      if (actions.supports(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        text.append(" 可向后滚动(scroll_backward)");
      if (!enabled) text.append(" 不可用");
      if (bounds.width() > 0 && bounds.height() > 0)
        text.append(" 中心=(")
            .append(bounds.centerX())
            .append(',')
            .append(bounds.centerY())
            .append(')');
      else text.append(" 无可触摸面积（仅语义节点）");
      text.append(" 区域=")
          .append(bounds.left)
          .append(',')
          .append(bounds.top)
          .append(',')
          .append(bounds.right)
          .append(',')
          .append(bounds.bottom)
          .append('\n');
    }
    if (password) return;
    for (int i = 0; i < node.getChildCount() && budget.childSlot(nodes.length()); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child != null)
        try {
          walk(child, nodes, text, depth + 1, budget, leases);
        } finally {
          child.recycle();
        }
    }
  }

  static String value(CharSequence value) {
    String text = value == null ? "" : value.toString();
    return text.substring(0, Math.min(1000, text.length()));
  }

  /** 历史只读虚拟屏输出门面，仍有完整遍历预算且不登记像素观察。 */
  public static String legacyText(AccessibilityNodeInfo root) {
    StringBuilder result = new StringBuilder();
    legacyWalk(
        root,
        result,
        0,
        new ScreenReadBudget(android.os.SystemClock::elapsedRealtime, () -> true),
        new int[] {0});
    return result.toString();
  }

  private static void legacyWalk(
      AccessibilityNodeInfo node,
      StringBuilder text,
      int depth,
      ScreenReadBudget budget,
      int[] count) {
    if (node == null || !budget.enter(depth, count[0]) || text.length() > 12000) return;
    boolean password = node.isPassword();
    boolean clickable = node.isClickable();
    boolean editable = node.isEditable();
    String label = password ? "[PASSWORD]" : value(node.getText());
    if (label.isEmpty()) label = value(node.getContentDescription());
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (AccessibilityDumpBounds.reportable(
        !label.isEmpty(),
        clickable || editable,
        bounds.left,
        bounds.top,
        bounds.right,
        bounds.bottom)) {
      text.append('[')
          .append(++count[0])
          .append("] \"")
          .append(label.replace('\n', ' '))
          .append('"');
      if (clickable) text.append(" 可点击");
      if (editable) text.append(" 可输入");
      text.append(" 中心=(")
          .append(bounds.centerX())
          .append(',')
          .append(bounds.centerY())
          .append(") 区域=")
          .append(bounds.left)
          .append(',')
          .append(bounds.top)
          .append(',')
          .append(bounds.right)
          .append(',')
          .append(bounds.bottom)
          .append('\n');
    }
    if (password) return;
    for (int i = 0; i < node.getChildCount() && budget.childSlot(count[0]); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child != null)
        try {
          legacyWalk(child, text, depth + 1, budget, count);
        } finally {
          child.recycle();
        }
    }
  }
}
