package com.deepseekharness.app.control;

import com.deepseekharness.app.util.ScreenWindowRegion;
import java.util.HashMap;
import java.util.Map;

/** Exact native-view identity and measured bounds. Arbitrary overlays never become exclusions. */
public final class OwnedScreenOverlay {
  private record Entry(int id, ScreenWindowRegion.Bounds bounds, boolean expanded, long epoch) {}

  private static final Map<String, Entry> entries = new HashMap<>();
  private static long epoch;
  private static volatile Runnable expansionListener;

  static void setExpansionListener(Runnable listener) {
    expansionListener = listener;
  }

  private OwnedScreenOverlay() {}

  public static void publish(
      String owner, int windowId, ScreenWindowRegion.Bounds bounds, boolean expanded) {
    publishMeasured(owner, windowId, bounds, expanded);
    Runnable listener = expansionListener;
    if (expanded && listener != null) listener.run();
  }

  private static synchronized void publishMeasured(
      String owner, int windowId, ScreenWindowRegion.Bounds bounds, boolean expanded) {
    if (owner == null || owner.isBlank())
      throw new IllegalArgumentException("OVERLAY_OWNER_INVALID");
    if (windowId < 0 || bounds == null || !bounds.valid()) {
      clear(owner);
      return;
    }
    Entry old = entries.get(owner);
    if (old != null && old.id == windowId && old.bounds.equals(bounds) && old.expanded == expanded)
      return;
    entries.put(owner, new Entry(windowId, bounds, expanded, ++epoch));
  }

  public static synchronized void clear(String owner) {
    if (entries.remove(owner) != null) epoch++;
  }

  public static synchronized ScreenWindowRegion.Exclusion match(
      int windowId, ScreenWindowRegion.Bounds actual, boolean ownPackage) {
    if (!ownPackage || actual == null || !actual.valid()) return null;
    for (Entry entry : entries.values())
      if (!entry.expanded && entry.id == windowId && contains(actual, entry.bounds))
        // Android's window may include decor/insets beyond the measured native View. Exclude
        // that entire current window, including its input area; never expose the padding as
        // usable pixels. The exact registered ID, package and publication revision still bind it.
        return new ScreenWindowRegion.Exclusion(windowId, entry.epoch, actual);
    return null;
  }

  private static boolean contains(
      ScreenWindowRegion.Bounds outer, ScreenWindowRegion.Bounds inner) {
    return inner.left() >= outer.left()
        && inner.top() >= outer.top()
        && inner.right() <= outer.right()
        && inner.bottom() <= outer.bottom();
  }

  public static synchronized boolean expanded() {
    for (Entry entry : entries.values()) if (entry.expanded) return true;
    return false;
  }
}
