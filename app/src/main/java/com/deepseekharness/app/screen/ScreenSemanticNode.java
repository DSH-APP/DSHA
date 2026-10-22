package com.deepseekharness.app.screen;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Objects;

/** 系统节点句柄及观察时的语义身份；刷新后身份改变不能沿用旧nodeId。 */
public final class ScreenSemanticNode implements AutoCloseable {
  public final AccessibilityNodeInfo node;
  private final String viewId, uniqueId, className, text, description;
  private final boolean password, editable, clickable, scrollable;

  public ScreenSemanticNode(AccessibilityNodeInfo source) {
    viewId = source.getViewIdResourceName();
    uniqueId = android.os.Build.VERSION.SDK_INT >= 33 ? source.getUniqueId() : null;
    className = String.valueOf(source.getClassName());
    password = source.isPassword();
    text = password ? "" : ScreenSnapshotReader.value(source.getText());
    description = password ? "" : ScreenSnapshotReader.value(source.getContentDescription());
    editable = source.isEditable();
    clickable = source.isClickable();
    scrollable = source.isScrollable();
    node = AccessibilityNodeInfo.obtain(source);
  }

  public boolean refreshMatches(int window) {
    return node.refresh()
        && node.getWindowId() == window
        && node.isEnabled()
        && password == node.isPassword()
        && editable == node.isEditable()
        && clickable == node.isClickable()
        && scrollable == node.isScrollable()
        && Objects.equals(viewId, node.getViewIdResourceName())
        && Objects.equals(className, String.valueOf(node.getClassName()))
        && (android.os.Build.VERSION.SDK_INT < 33 || Objects.equals(uniqueId, node.getUniqueId()))
        && (password
            || text.equals(ScreenSnapshotReader.value(node.getText()))
                && description.equals(ScreenSnapshotReader.value(node.getContentDescription())));
  }

  @Override
  public void close() {
    node.recycle();
  }
}
