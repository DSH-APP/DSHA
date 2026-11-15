package com.deepseekharness.app.screen;

import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;

/** Only advertise actions reported by this current Android node. */
final class ScreenNodeActions {
  private static final String[] NAMES = {
    "click", "long_click", "scroll_forward", "scroll_backward", "focus", "set_text"
  };
  private static final int[] ACTIONS = {
    AccessibilityNodeInfo.ACTION_CLICK,
    AccessibilityNodeInfo.ACTION_LONG_CLICK,
    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
    AccessibilityNodeInfo.ACTION_FOCUS,
    AccessibilityNodeInfo.ACTION_SET_TEXT
  };

  private ScreenNodeActions() {}

  static int action(String name) {
    for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return ACTIONS[i];
    return -1;
  }

  static boolean scroll(int action) {
    return action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
  }

  static boolean supported(AccessibilityNodeInfo node, int action) {
    return snapshot(node).supports(action);
  }

  static JSONArray names(AccessibilityNodeInfo node) {
    return snapshot(node).names();
  }

  static ActionSnapshot snapshot(AccessibilityNodeInfo node) {
    int mask = 0;
    if (node == null) return new ActionSnapshot(mask);
    var availableActions = node.getActionList();
    if (availableActions != null)
      for (var available : availableActions) if (available != null) mask |= bit(available.getId());
    return new ActionSnapshot(mask);
  }

  private static int bit(int action) {
    for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i] == action) return 1 << i;
    return 0;
  }

  static boolean supportsMask(int mask, int action) {
    int actionBit = bit(action);
    return actionBit != 0 && (mask & actionBit) != 0;
  }

  static final class ActionSnapshot {
    private final int mask;

    int mask() {
      return mask;
    }

    private ActionSnapshot(int mask) {
      this.mask = mask;
    }

    boolean supports(int action) {
      return supportsMask(mask, action);
    }

    boolean hasAny() {
      return mask != 0;
    }

    JSONArray names() {
      var result = new JSONArray();
      for (int i = 0; i < NAMES.length; i++) if ((mask & (1 << i)) != 0) result.put(NAMES[i]);
      return result;
    }
  }
}
