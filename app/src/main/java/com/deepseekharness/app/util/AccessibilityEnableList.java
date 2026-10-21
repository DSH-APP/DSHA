package com.deepseekharness.app.util;

/** Preserve every other service verbatim. Never remove/reorder services to enable this APK. */
public final class AccessibilityEnableList {
  private AccessibilityEnableList() {}

  /** Exact Android component identity, including package-relative service names. */
  public static boolean containsOwn(String before, String own) {
    if (before == null || before.length() > 65536 || own == null) return false;
    String[] selected = own.split("/", -1);
    if (selected.length != 2 || selected[0].isEmpty() || selected[1].isEmpty()) return false;
    String selectedClass = selected[1].startsWith(".") ? selected[0] + selected[1] : selected[1];
    for (String item : before.split(":", -1)) {
      String[] candidate = item.trim().split("/", -1);
      if (candidate.length != 2 || !candidate[0].equals(selected[0])) continue;
      String name = candidate[1].startsWith(".") ? candidate[0] + candidate[1] : candidate[1];
      if (name.equals(selectedClass)) return true;
    }
    return false;
  }

  public static String addOwn(String before, String own) {
    if (own == null || !own.matches("[A-Za-z][A-Za-z0-9_.]+/[A-Za-z.][A-Za-z0-9_.$]*"))
      throw new IllegalArgumentException("ACCESSIBILITY_COMPONENT_INVALID");
    if (before == null || before.isEmpty()) return own;
    if (before.length() > 65536) throw new IllegalArgumentException("ACCESSIBILITY_LIST_TOO_LARGE");
    for (String component : before.split(":", -1)) {
      if (component.equals(own)) return before;
      // Android may serialize the same component with a relative service class.
      String[] existing = component.split("/", -1), selected = own.split("/", -1);
      if (existing.length == 2 && selected.length == 2 && existing[0].equals(selected[0])) {
        String expanded = existing[1].startsWith(".") ? existing[0] + existing[1] : existing[1];
        if (expanded.equals(selected[1])) return before;
      }
    }
    return before + (before.endsWith(":") ? "" : ":") + own;
  }
}
