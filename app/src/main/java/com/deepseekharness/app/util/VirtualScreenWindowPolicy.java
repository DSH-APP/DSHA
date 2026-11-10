package com.deepseekharness.app.util;

/** 同一虚拟显示的根窗口排序；所有读屏与动作入口使用同一规则。 */
public final class VirtualScreenWindowPolicy {
  private VirtualScreenWindowPolicy() {}

  /** 与 AccessibilityWindowInfo.TYPE_* 对齐；util 不引用 Android API，只保留数值。 */
  public static final int TYPE_OTHER = 0;

  public static final int TYPE_APPLICATION = 1;
  public static final int TYPE_INPUT_METHOD = 2;
  public static final int TYPE_SYSTEM = 3;
  public static final int TYPE_ACCESSIBILITY_OVERLAY = 4;

  public static final class Window {
    public final int display, id, type, layer;
    public final boolean application, focused, active;

    /** 旧签名：application=true 映射为 TYPE_APPLICATION，其余为 TYPE_OTHER。 */
    public Window(
        int display, int id, int layer, boolean application, boolean focused, boolean active) {
      this(display, id, application ? TYPE_APPLICATION : TYPE_OTHER, layer, focused, active);
    }

    public Window(int display, int id, int type, int layer, boolean focused, boolean active) {
      this.display = display;
      this.id = id;
      this.type = type;
      this.layer = layer;
      this.application = type == TYPE_APPLICATION;
      this.focused = focused;
      this.active = active;
    }
  }

  /**
   * 一次选择结果。index=-1 可能是「多个应用窗口并列」也可能是「根本没有应用窗口」；调用方只能按
   * ambiguous/applicationWindows 区分，不能把 -1 当成「选一个最上层的」。
   */
  public static final class Selection {
    public final int index;
    public final boolean ambiguous;
    public final int applicationWindows, inputMethodWindows, systemWindows;
    public final int focusedWindowId, activeWindowId;

    Selection(
        int index,
        boolean ambiguous,
        int applicationWindows,
        int inputMethodWindows,
        int systemWindows,
        int focusedWindowId,
        int activeWindowId) {
      this.index = index;
      this.ambiguous = ambiguous;
      this.applicationWindows = applicationWindows;
      this.inputMethodWindows = inputMethodWindows;
      this.systemWindows = systemWindows;
      this.focusedWindowId = focusedWindowId;
      this.activeWindowId = activeWindowId;
    }
  }

  /**
   * Select by window metadata before requesting any root. A root failure never elects a background app.
   * 计数/焦点窗口 id 只用于诊断，不参与选择。
   */
  public static Selection selectDetailed(int display, java.util.List<Window> windows) {
    int selected = -1, bestRank = -1, bestLayer = Integer.MIN_VALUE;
    int application = 0, inputMethod = 0, system = 0;
    int focusedId = -1, activeId = -1;
    boolean ambiguous = false;
    for (int i = 0; i < windows.size(); i++) {
      Window window = windows.get(i);
      if (window.display != display || window.id < 0) continue;
      switch (window.type) {
        case TYPE_APPLICATION:
          application++;
          break;
        case TYPE_INPUT_METHOD:
          inputMethod++;
          break;
        case TYPE_SYSTEM:
          system++;
          break;
        default:
          break;
      }
      if (window.focused && focusedId < 0) focusedId = window.id;
      if (window.active && activeId < 0) activeId = window.id;
      if (!window.application) continue;
      int rank = rank(window.focused, window.active);
      if (selected < 0 || rank > bestRank || rank == bestRank && window.layer > bestLayer) {
        selected = i;
        bestRank = rank;
        bestLayer = window.layer;
        ambiguous = false;
      } else if (rank == bestRank && window.layer == bestLayer) ambiguous = true;
    }
    return new Selection(
        ambiguous ? -1 : selected,
        ambiguous,
        application,
        inputMethod,
        system,
        focusedId,
        activeId);
  }

  /** 旧入口：只返回下标；需要歧义/计数时用 {@link #selectDetailed}。 */
  public static int select(int display, java.util.List<Window> windows) {
    return selectDetailed(display, windows).index;
  }

  public static int rank(boolean focused, boolean active) {
    return focused ? 2 : active ? 1 : 0;
  }

  public static boolean approved(
      int display, String approvedPackage, int currentDisplay, String currentPackage) {
    return display > 0
        && display == currentDisplay
        && approvedPackage != null
        && !approvedPackage.isEmpty()
        && approvedPackage.equals(currentPackage);
  }
}
