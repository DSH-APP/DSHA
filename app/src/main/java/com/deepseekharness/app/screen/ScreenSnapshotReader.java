package com.deepseekharness.app.screen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.util.AccessibilityDumpBounds;
import com.deepseekharness.app.util.AccessibilityTreeBudget;
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
      AccessibilityTreeBudget budget = new AccessibilityTreeBudget();
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
      AccessibilityTreeBudget budget,
      ScreenNodeLeaseStore<ScreenSemanticNode> leases)
      throws Exception {
    if (node == null || !budget.enter(depth, nodes.length()) || text.length() > 12000) return;
    String label = node.isPassword() ? "[PASSWORD]" : value(node.getText());
    String description = node.isPassword() ? "" : value(node.getContentDescription());
    if (label.isEmpty()) label = description;
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (AccessibilityDumpBounds.reportable(
        !label.isEmpty(),
        node.isClickable() || node.isEditable() || node.isScrollable(),
        bounds.left,
        bounds.top,
        bounds.right,
        bounds.bottom)) {
      String id = leases.add(new ScreenSemanticNode(node));
      nodes.put(
          new JSONObject()
              .put("nodeId", id)
              .put("text", label)
              .put("description", description)
              .put("clickable", node.isClickable())
              .put("editable", node.isEditable())
              .put("scrollable", node.isScrollable())
              .put("enabled", node.isEnabled())
              .put("checked", node.isChecked())
              .put("boundsHaveArea", bounds.width() > 0 && bounds.height() > 0)
              .put(
                  "bounds",
                  new JSONArray(new int[] {bounds.left, bounds.top, bounds.right, bounds.bottom})));
      text.append('[')
          .append(nodes.length())
          .append("] \"")
          .append(label.replace('\n', ' '))
          .append('"');
      if (node.isClickable()) text.append(" 可点击");
      if (node.isEditable()) text.append(" 可输入");
      if (!node.isEnabled()) text.append(" 不可用");
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
    if (node.isPassword()) return;
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
    legacyWalk(root, result, 0, new AccessibilityTreeBudget(), new int[] {0});
    return result.toString();
  }

  private static void legacyWalk(
      AccessibilityNodeInfo node,
      StringBuilder text,
      int depth,
      AccessibilityTreeBudget budget,
      int[] count) {
    if (node == null || !budget.enter(depth, count[0]) || text.length() > 12000) return;
    String label = node.isPassword() ? "[PASSWORD]" : value(node.getText());
    if (label.isEmpty()) label = value(node.getContentDescription());
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (AccessibilityDumpBounds.reportable(
        !label.isEmpty(),
        node.isClickable() || node.isEditable(),
        bounds.left,
        bounds.top,
        bounds.right,
        bounds.bottom)) {
      text.append('[')
          .append(++count[0])
          .append("] \"")
          .append(label.replace('\n', ' '))
          .append('"');
      if (node.isClickable()) text.append(" 可点击");
      if (node.isEditable()) text.append(" 可输入");
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
    if (node.isPassword()) return;
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
