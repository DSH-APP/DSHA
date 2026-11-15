package com.deepseekharness.app.util;

import java.util.List;
import java.util.Map;

/**
 * 虚拟屏窗口证据（T98）：把无障碍窗口清单里与目标窗口身份有关的字段，整理成受控的最小只读快照。
 *
 * <p>只输出 displayId、窗口 id/type/layer/bounds/focused/active、标题和包名；不包含节点文本、控件内容、
 * 截图或完整窗口清单。标题去掉控制字符、折叠空白并截断，包名按 Android 包名格式校验，不合格的值直接
 * 省略。证据只用于解释失败和给出 nextAction，<b>不能用来挑选、替换或自动确认窗口</b>：语义路径仍然
 * 只认 {@link VirtualScreenWindowPolicy} 选出的唯一窗口。
 *
 * <p>纯逻辑：不 import Android API，也不依赖 org.json；字段表只含 String/Integer/Boolean。
 */
public final class VirtualScreenWindowEvidence {
  /** 标题最多保留的码点数；超出部分截断并追加省略号。 */
  public static final int TITLE_LIMIT = 64;

  public static final int PACKAGE_LIMIT = 200;

  private static final String TRUNCATED = "…";

  private VirtualScreenWindowEvidence() {}

  /** 无障碍窗口清单里的一行；type 数值见 {@link VirtualScreenWindowPolicy}。 */
  public static final class Window {
    public final int displayId, id, type, layer;
    public final int left, top, right, bottom;
    public final boolean focused, active;
    public final String title;

    public Window(
        int displayId,
        int id,
        int type,
        int layer,
        int left,
        int top,
        int right,
        int bottom,
        boolean focused,
        boolean active,
        String title) {
      this.displayId = displayId;
      this.id = id;
      this.type = type;
      this.layer = layer;
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
      this.focused = focused;
      this.active = active;
      this.title = title;
    }
  }

  /** 一次只读快照；target 只在窗口唯一可选时存在，package 在宿主读到 root 后才补。 */
  public static final class Snapshot {
    public final int selectedIndex;
    public final boolean ambiguous;
    public final int applicationWindows, inputMethodWindows, systemWindows;
    public final int focusedWindowId, activeWindowId;
    public final Window target;
    public final String targetPackage;

    Snapshot(
        int selectedIndex,
        boolean ambiguous,
        int applicationWindows,
        int inputMethodWindows,
        int systemWindows,
        int focusedWindowId,
        int activeWindowId,
        Window target,
        String targetPackage) {
      this.selectedIndex = selectedIndex;
      this.ambiguous = ambiguous;
      this.applicationWindows = applicationWindows;
      this.inputMethodWindows = inputMethodWindows;
      this.systemWindows = systemWindows;
      this.focusedWindowId = focusedWindowId;
      this.activeWindowId = activeWindowId;
      this.target = target;
      this.targetPackage = targetPackage;
    }

    /**
     * 目标窗口字段；没有唯一目标时只给计数和歧义，绝不输出“猜出来的”窗口身份。
     *
     * <p>键名固定为 ASCII；数值都是受控字段：窗口 id/layer 是整数，bounds 是 "left,top,right,bottom"，
     * title 已去控制字符并截断，package 已按包名格式校验。
     */
    public Map<String, Object> fields() {
      var fields = new java.util.LinkedHashMap<String, Object>();
      fields.put("applicationWindowCount", applicationWindows);
      fields.put("systemWindowCount", systemWindows);
      fields.put("inputMethodWindowCount", inputMethodWindows);
      fields.put("focusedWindowId", focusedWindowId);
      fields.put("activeWindowId", activeWindowId);
      fields.put("windowAmbiguous", ambiguous);
      if (target != null) {
        fields.put("targetWindowDisplayId", target.displayId);
        fields.put("targetWindowId", target.id);
        fields.put("targetWindowType", typeName(target.type));
        fields.put("targetWindowLayer", target.layer);
        fields.put(
            "targetWindowBounds",
            target.left + "," + target.top + "," + target.right + "," + target.bottom);
        fields.put("targetWindowFocused", target.focused);
        fields.put("targetWindowActive", target.active);
        if (target.title != null) fields.put("targetWindowTitle", target.title);
      }
      if (targetPackage != null) fields.put("targetWindowPackage", targetPackage);
      return fields;
    }

    /** 只补包名，不改变选择结果；空值/非法值保持省略。 */
    public Snapshot withTargetPackage(String packageName) {
      if (target == null) return this;
      String safe = sanitizePackage(packageName);
      if (safe == null || safe.equals(targetPackage)) return this;
      return new Snapshot(
          selectedIndex,
          ambiguous,
          applicationWindows,
          inputMethodWindows,
          systemWindows,
          focusedWindowId,
          activeWindowId,
          target,
          safe);
    }
  }

  /**
   * 按与读屏/动作路径相同的排序规则整理证据。返回的 selectedIndex 是 {@code windows} 的下标（-1 表示
   * 没有唯一应用窗口）；调用方必须用它取同一个窗口，不能再用别的规则重选。
   */
  public static Snapshot of(int display, List<Window> windows) {
    var candidates = new java.util.ArrayList<VirtualScreenWindowPolicy.Window>(windows.size());
    for (Window window : windows)
      candidates.add(
          new VirtualScreenWindowPolicy.Window(
              window.displayId,
              window.id,
              window.type,
              window.layer,
              window.focused,
              window.active));
    var selection = VirtualScreenWindowPolicy.selectDetailed(display, candidates);
    Window target = null;
    if (selection.index >= 0) {
      Window source = windows.get(selection.index);
      target =
          new Window(
              source.displayId,
              source.id,
              source.type,
              source.layer,
              source.left,
              source.top,
              source.right,
              source.bottom,
              source.focused,
              source.active,
              sanitizeTitle(source.title));
    }
    return new Snapshot(
        selection.index,
        selection.ambiguous,
        selection.applicationWindows,
        selection.inputMethodWindows,
        selection.systemWindows,
        selection.focusedWindowId,
        selection.activeWindowId,
        target,
        null);
  }

  /** 无障碍窗口类型 → 稳定 ASCII 名；未知类型不进入目标（目标只可能是 APPLICATION）。 */
  public static String typeName(int type) {
    switch (type) {
      case VirtualScreenWindowPolicy.TYPE_APPLICATION:
        return "APPLICATION";
      case VirtualScreenWindowPolicy.TYPE_INPUT_METHOD:
        return "INPUT_METHOD";
      case VirtualScreenWindowPolicy.TYPE_SYSTEM:
        return "SYSTEM";
      case VirtualScreenWindowPolicy.TYPE_ACCESSIBILITY_OVERLAY:
        return "ACCESSIBILITY_OVERLAY";
      default:
        return "OTHER";
    }
  }

  /** 去掉控制字符、折叠空白、截断到 {@link #TITLE_LIMIT} 个码点；空标题返回 null。 */
  public static String sanitizeTitle(String raw) {
    if (raw == null || raw.isEmpty()) return null;
    var out = new StringBuilder();
    boolean pendingSpace = false;
    int count = 0;
    for (int index = 0; index < raw.length(); ) {
      int point = raw.codePointAt(index);
      index += Character.charCount(point);
      if (Character.isISOControl(point) || Character.isWhitespace(point)) {
        if (out.length() > 0) pendingSpace = true;
        continue;
      }
      if (pendingSpace) {
        if (count >= TITLE_LIMIT) {
          out.append(TRUNCATED);
          break;
        }
        out.append(' ');
        pendingSpace = false;
        count++;
      }
      if (count >= TITLE_LIMIT) {
        out.append(TRUNCATED);
        break;
      }
      out.appendCodePoint(point);
      count++;
    }
    return out.length() == 0 ? null : out.toString();
  }

  /** 只接受 Android 包名字母表（字母/数字/下划线/点），否则省略，避免把任意文本带进诊断。 */
  public static String sanitizePackage(String raw) {
    if (raw == null || raw.isEmpty() || raw.length() > PACKAGE_LIMIT) return null;
    for (int index = 0; index < raw.length(); index++) {
      char c = raw.charAt(index);
      boolean valid =
          c >= 'a' && c <= 'z'
              || c >= 'A' && c <= 'Z'
              || c >= '0' && c <= '9'
              || c == '.'
              || c == '_';
      if (!valid) return null;
    }
    return raw;
  }
}
