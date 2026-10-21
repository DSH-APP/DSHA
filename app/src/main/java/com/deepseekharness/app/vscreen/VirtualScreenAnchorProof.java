package com.deepseekharness.app.vscreen;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.deepseekharness.app.screen.ScreenRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Private complete semantic proof. Neither strings nor node handles are diagnostic output. */
@androidx.annotation.RequiresApi(30)
final class VirtualScreenAnchorProof implements AutoCloseable {
  static final int NODE_LIMIT = 4096, DEPTH_LIMIT = 64, CANDIDATE_LIMIT = 128;
  static final long METADATA_LIMIT = 1024L * 1024;
  final List<Candidate> candidates = new ArrayList<>();
  final List<Window> windows = new ArrayList<>();
  final AccessibilityNodeInfo root;
  final int display, window;
  final String packageName;
  final long connectionEpoch;
  byte[] digest;
  long bytes;
  int nodes;
  private final MessageDigest hash;
  private boolean closed;
  private final long deadline = android.os.SystemClock.elapsedRealtime() + 1000;

  static final class Unavailable extends Exception {
    final String reason;

    Unavailable(String reason) {
      super(reason);
      this.reason = reason;
    }
  }

  static final class Candidate {
    final AccessibilityNodeInfo node;
    final Rect bounds;
    final String path;

    Candidate(AccessibilityNodeInfo node, Rect bounds, String path) {
      this.node = AccessibilityNodeInfo.obtain(node);
      this.bounds = new Rect(bounds);
      this.path = path;
    }
  }

  private static final class Window {
    final int id;
    final Rect bounds;

    Window(int id, Rect bounds) {
      this.id = id;
      this.bounds = new Rect(bounds);
    }
  }

  private VirtualScreenAnchorProof(AccessibilityNodeInfo root, ScreenRequestContext context)
      throws Exception {
    this.root = root;
    display = context.target().displayId;
    window = context.target().windowId;
    packageName = context.target().packageName;
    connectionEpoch = context.serviceEpoch();
    hash = MessageDigest.getInstance("SHA-256");
  }

  static VirtualScreenAnchorProof capture(
      AccessibilityService service, ScreenRequestContext context) throws Exception {
    if (service == null || !context.current())
      throw new Unavailable("ANCHOR_ACCESSIBILITY_UNAVAILABLE");
    AccessibilityServiceInfo info = service.getServiceInfo();
    if (info == null
        || (info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0
        || (info.getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)
            == 0) throw new Unavailable("ANCHOR_WINDOW_INVENTORY_UNAVAILABLE");
    var inventory = service.getWindowsOnAllDisplays();
    if (inventory == null) throw new Unavailable("ANCHOR_WINDOW_INVENTORY_UNAVAILABLE");
    AccessibilityNodeInfo root = null;
    VirtualScreenAnchorProof proof = null;
    try {
      List<AccessibilityWindowInfo> windows = inventory.get(context.target().displayId);
      if (windows == null || windows.isEmpty() || windows.size() > 64)
        throw new Unavailable("ANCHOR_WINDOW_INVENTORY_UNAVAILABLE");
      for (AccessibilityWindowInfo window : windows) {
        if (window.getId() == context.target().windowId) {
          if (root != null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION)
            throw new Unavailable("ANCHOR_WINDOW_AMBIGUOUS");
          root = window.getRoot();
        }
      }
      if (root == null) throw new Unavailable("ANCHOR_TREE_UNAVAILABLE");
      context.validateRoot(context.target().displayId, root);
      proof = new VirtualScreenAnchorProof(root, context);
      proof.number(proof.display);
      proof.number(proof.window);
      proof.text(proof.packageName);
      windows = new ArrayList<>(windows);
      windows.sort(Comparator.comparingInt(AccessibilityWindowInfo::getId));
      int last = -1;
      for (AccessibilityWindowInfo window : windows) {
        if (window.getDisplayId() != proof.display || window.getId() == last)
          throw new Unavailable("ANCHOR_WINDOW_AMBIGUOUS");
        last = window.getId();
        Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) throw new Unavailable("ANCHOR_WINDOW_GEOMETRY_UNAVAILABLE");
        proof.bytes += 512L + 4L * window.getChildCount();
        if (proof.bytes > METADATA_LIMIT) throw new Unavailable("ANCHOR_TREE_BUDGET");
        proof.windows.add(new Window(window.getId(), bounds));
        proof.number(window.getId());
        proof.number(window.getType());
        proof.number(window.getLayer());
        proof.rect(bounds);
        proof.flag(window.isFocused());
        proof.flag(window.isActive());
        proof.flag(window.isAccessibilityFocused());
        proof.text(window.getTitle());
        AccessibilityWindowInfo parent = window.getParent();
        try {
          proof.number(parent == null ? -1 : parent.getId());
        } finally {
          if (parent != null) parent.recycle();
        }
        if (window.getChildCount() > 64) throw new Unavailable("ANCHOR_WINDOW_BUDGET");
        proof.number(window.getChildCount());
        for (int i = 0; i < window.getChildCount(); i++) {
          AccessibilityWindowInfo child = window.getChild(i);
          if (child == null) throw new Unavailable("ANCHOR_WINDOW_INCOMPLETE");
          try {
            proof.number(child.getId());
          } finally {
            child.recycle();
          }
        }
      }
      proof.walk(root, "0", 0);
      context.validateRoot(proof.display, root);
      proof.digest = proof.hash.digest();
      return proof;
    } catch (Exception error) {
      if (proof != null) proof.close();
      else if (root != null) root.recycle();
      throw error;
    } finally {
      // getWindowsOnAllDisplays transfers windows for every display, not just the target display.
      var released =
          java.util.Collections.newSetFromMap(
              new java.util.IdentityHashMap<AccessibilityWindowInfo, Boolean>());
      for (int i = 0; i < inventory.size(); i++) {
        List<AccessibilityWindowInfo> owned = inventory.valueAt(i);
        if (owned == null) continue;
        for (AccessibilityWindowInfo window : owned)
          if (window != null && released.add(window))
            try {
              window.recycle();
            } catch (RuntimeException unavailable) {
            }
      }
    }
  }

  private void walk(AccessibilityNodeInfo node, String path, int depth) throws Exception {
    if (android.os.SystemClock.elapsedRealtime() > deadline)
      throw new Unavailable("ANCHOR_PROOF_DEADLINE");
    if (depth > DEPTH_LIMIT || ++nodes > NODE_LIMIT) throw new Unavailable("ANCHOR_TREE_BUDGET");
    bytes += 512;
    if (bytes > METADATA_LIMIT) throw new Unavailable("ANCHOR_TREE_BUDGET");
    if (node.getWindowId() != window) throw new Unavailable("ANCHOR_TREE_INCOMPLETE");
    text(path);
    number(node.hashCode());
    text(node.getClassName());
    text(node.getPackageName());
    text(node.getViewIdResourceName());
    if (android.os.Build.VERSION.SDK_INT >= 33) text(node.getUniqueId());
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    rect(bounds);
    flag(node.isVisibleToUser());
    flag(node.isEnabled());
    flag(node.isClickable());
    flag(node.isLongClickable());
    flag(node.isEditable());
    flag(node.isScrollable());
    flag(node.isCheckable());
    flag(node.isChecked());
    flag(node.isSelected());
    flag(node.isFocusable());
    flag(node.isFocused());
    flag(node.isAccessibilityFocused());
    flag(node.isPassword());
    flag(node.isContentInvalid());
    flag(node.isDismissable());
    flag(node.isMultiLine());
    flag(node.isContextClickable());
    flag(node.isHeading());
    flag(node.isShowingHintText());
    flag(node.isScreenReaderFocusable());
    flag(node.isImportantForAccessibility());
    flag(node.isTextEntryKey());
    text(node.getText());
    text(node.getContentDescription());
    text(node.getStateDescription());
    text(node.getHintText());
    text(node.getError());
    text(node.getTooltipText());
    text(node.getPaneTitle());
    number(node.getTextSelectionStart());
    number(node.getTextSelectionEnd());
    number(node.getInputType());
    number(node.getLiveRegion());
    number(node.getDrawingOrder());
    number(node.getMaxTextLength());
    number(node.getMovementGranularities());
    var range = node.getRangeInfo();
    flag(range != null);
    if (range != null) {
      number(range.getType());
      number(Float.floatToIntBits(range.getMin()));
      number(Float.floatToIntBits(range.getMax()));
      number(Float.floatToIntBits(range.getCurrent()));
    }
    var collection = node.getCollectionInfo();
    flag(collection != null);
    if (collection != null) {
      number(collection.getRowCount());
      number(collection.getColumnCount());
      flag(collection.isHierarchical());
      number(collection.getSelectionMode());
    }
    var item = node.getCollectionItemInfo();
    flag(item != null);
    if (item != null) {
      number(item.getRowIndex());
      number(item.getRowSpan());
      number(item.getColumnIndex());
      number(item.getColumnSpan());
      flag(item.isHeading());
      flag(item.isSelected());
    }
    // Primitive extras are compared in full; unknown parcelables are not silently ignored.
    extras(node.getExtras());
    var actions = new ArrayList<>(node.getActionList());
    if (actions.size() > 64) throw new Unavailable("ANCHOR_TREE_BUDGET");
    actions.sort(Comparator.comparingInt(AccessibilityNodeInfo.AccessibilityAction::getId));
    number(actions.size());
    boolean click = false;
    for (var action : actions) {
      number(action.getId());
      text(action.getLabel());
      click |= action.getId() == AccessibilityNodeInfo.ACTION_CLICK;
    }
    if (click
        && node.isClickable()
        && node.isEnabled()
        && node.isVisibleToUser()
        && !bounds.isEmpty()) {
      if (candidates.size() >= CANDIDATE_LIMIT) throw new Unavailable("ANCHOR_CANDIDATE_BUDGET");
      bytes += 512;
      if (bytes > METADATA_LIMIT) throw new Unavailable("ANCHOR_TREE_BUDGET");
      candidates.add(new Candidate(node, bounds, path));
    }
    int children = node.getChildCount();
    if (children < 0 || children > NODE_LIMIT - nodes) throw new Unavailable("ANCHOR_TREE_BUDGET");
    number(children);
    for (int i = 0; i < children; i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) throw new Unavailable("ANCHOR_TREE_INCOMPLETE");
      try {
        walk(child, path + "/" + i, depth + 1);
      } finally {
        child.recycle();
      }
    }
  }

  Candidate select(int x, int y, int width, int height) throws Unavailable {
    Candidate result = null;
    if (x < 0 || y < 0 || x >= width || y >= height) throw new Unavailable("INVALID_COORDINATE");
    for (Candidate candidate : candidates) {
      if (!candidate.bounds.contains(x, y)) continue;
      if (result != null) throw new Unavailable("ANCHOR_AMBIGUOUS");
      Rect r = candidate.bounds;
      if (r.left < 0 || r.top < 0 || r.right > width || r.bottom > height)
        throw new Unavailable("ANCHOR_GEOMETRY_UNAVAILABLE");
      result = candidate;
    }
    if (result == null) throw new Unavailable("ANCHOR_NODE_UNAVAILABLE");
    Rect region = region(result.bounds, width, height);
    if (!unobscured(region)) throw new Unavailable("ANCHOR_OCCLUDED");
    return result;
  }

  Candidate same(Candidate original) {
    for (Candidate candidate : candidates)
      if (candidate.path.equals(original.path)
          && candidate.node.equals(original.node)
          && candidate.bounds.equals(original.bounds)) return candidate;
    return null;
  }

  static Rect region(Rect node, int width, int height) {
    return new Rect(
        Math.max(0, node.left - 8),
        Math.max(0, node.top - 8),
        Math.min(width, node.right + 8),
        Math.min(height, node.bottom + 8));
  }

  boolean unobscured(Rect region) {
    for (Window other : windows)
      if (other.id != window && Rect.intersects(other.bounds, region)) return false;
    return true;
  }

  boolean sameAs(VirtualScreenAnchorProof other) {
    return other != null
        && connectionEpoch == other.connectionEpoch
        && MessageDigest.isEqual(digest, other.digest);
  }

  private void rect(Rect rect) {
    number(rect.left);
    number(rect.top);
    number(rect.right);
    number(rect.bottom);
  }

  private void flag(boolean value) {
    hash.update((byte) (value ? 1 : 0));
  }

  private void number(int value) {
    hash.update((byte) (value >>> 24));
    hash.update((byte) (value >>> 16));
    hash.update((byte) (value >>> 8));
    hash.update((byte) value);
  }

  private void text(CharSequence value) throws Unavailable {
    if (value == null) {
      number(-1);
      return;
    }
    if (value.length() > METADATA_LIMIT / 2) throw new Unavailable("ANCHOR_TREE_BUDGET");
    byte[] encoded = value.toString().getBytes(StandardCharsets.UTF_8);
    bytes += Math.max(encoded.length, 2L * value.length());
    if (bytes > METADATA_LIMIT) throw new Unavailable("ANCHOR_TREE_BUDGET");
    number(encoded.length);
    hash.update(encoded);
  }

  private void extras(android.os.Bundle values) throws Unavailable {
    if (values.size() > 64) throw new Unavailable("ANCHOR_SEMANTIC_EXTRAS_UNSUPPORTED");
    var keys = new ArrayList<>(values.keySet());
    java.util.Collections.sort(keys);
    number(keys.size());
    for (String key : keys) {
      text(key);
      Object value = values.get(key);
      if (value == null) {
        text("null");
      } else if (value instanceof CharSequence sequence) {
        text("text");
        text(sequence);
      } else if (value instanceof Number || value instanceof Boolean) {
        text(value.getClass().getName());
        text(value.toString());
      } else if (value instanceof int[] array && array.length <= 4096) {
        bytes += 4L * array.length;
        if (bytes > METADATA_LIMIT) throw new Unavailable("ANCHOR_TREE_BUDGET");
        text("int[]");
        number(array.length);
        for (int item : array) number(item);
      } else if (value instanceof String[] array && array.length <= 128) {
        text("String[]");
        number(array.length);
        for (String item : array) text(item);
      } else throw new Unavailable("ANCHOR_SEMANTIC_EXTRAS_UNSUPPORTED");
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    for (Candidate candidate : candidates) {
      try {
        candidate.node.recycle();
      } catch (RuntimeException unavailable) {
      }
    }
    candidates.clear();
    try {
      root.recycle();
    } catch (RuntimeException unavailable) {
    }
  }
}
