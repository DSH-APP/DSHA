package com.deepseekharness.app.util;

import java.util.List;

/** Physical display policy. Virtual displays retain their separate application-window policy. */
public final class MainScreenWindowPolicy {
  private MainScreenWindowPolicy() {}

  public enum Kind {
    APPLICATION,
    SYSTEM,
    INPUT_METHOD,
    UNSUPPORTED
  }

  public record Window(
      int display,
      int id,
      int layer,
      Kind kind,
      boolean focused,
      boolean active,
      ScreenWindowRegion.Bounds bounds,
      ScreenWindowRegion.Exclusion ownedExclusion) {
    public Window(
        int display,
        int id,
        int layer,
        Kind kind,
        boolean focused,
        boolean active,
        ScreenWindowRegion.Bounds bounds) {
      this(display, id, layer, kind, focused, active, bounds, null);
    }
  }

  public record Selection(int index, String diagnostic) {}

  /** IME is an occluder of the focused editor, never an excuse to choose a background app. */
  public static int select(List<Window> windows) {
    return selection(windows).index;
  }

  public static Selection selection(List<Window> windows) {
    int selected = -1, layer = Integer.MIN_VALUE;
    boolean ambiguous = false;
    for (int i = 0; i < windows.size(); i++) {
      Window w = windows.get(i);
      if (w.display != 0
          || w.id < 0
          || !w.focused && !w.active
          || w.kind == Kind.INPUT_METHOD
          || w.ownedExclusion != null) continue;
      if (selected < 0 || w.layer > layer) {
        selected = i;
        layer = w.layer;
        ambiguous = false;
      } else if (w.layer == layer) ambiguous = true;
    }
    if (ambiguous) return new Selection(-1, "AMBIGUOUS_INTERACTIVE_WINDOWS");
    if (selected < 0) return new Selection(-1, "NO_FOCUSED_OR_ACTIVE_WINDOW");
    Window w = windows.get(selected);
    if (w.kind != Kind.APPLICATION && w.kind != Kind.SYSTEM)
      return new Selection(-1, "UNSUPPORTED_INTERACTIVE_WINDOW");
    return new Selection(selected, "READABLE");
  }

  /**
   * #210：未登记（或登记后窗口重建、windowId 漂移）的自有<b>非焦点</b>悬浮窗，
   * 按包名身份整窗排除，不计为目标遮挡。包名在 Android 内是精确身份，不存在冒名；
   * 排除区内的坐标轨迹仍被 {@code ScreenWindowRegion} 拒绝，完全盖满时依旧
   * 无可点面积 —— 行为与登记路径一致，只是不再要求每个悬浮窗都维护登记。
   * 可聚焦的自有 Activity 窗口不适用：它们可能是观察目标本身。
   */
  public static ScreenWindowRegion.Exclusion ownFallbackExclusion(
      boolean ownPackage,
      boolean focused,
      int windowId,
      ScreenWindowRegion.Bounds measured,
      ScreenWindowRegion.Exclusion registered) {
    if (registered != null || !ownPackage || focused || measured == null || !measured.valid())
      return registered;
    return new ScreenWindowRegion.Exclusion(windowId, 0, measured);
  }

  /** 贴边容差：系统栏/厂商栏与目标窗口边常有 1–2px 取整偏差。 */
  private static final int EDGE_TOLERANCE_PX = 2;

  /** 完整边装饰：沿目标边覆盖 ≥80% 才按整条边裁切（ZUI 居中 taskbar 覆盖 84%）。 */
  private static final int COMPLETE_EDGE_COVERAGE_PERCENT = 80;

  /** 贴边小浮层：沿边覆盖不足时，从该边向内延伸不超过目标垂直方向的 1/4 仍按该边裁切。 */
  private static final int LOCAL_EDGE_DEPTH_DIVISOR = 4;

  /** 可裁切的边；枚举顺序固定，深度相同时按 上→下→左→右 稳定选择。 */
  private enum Edge {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT
  }

  /**
   * 物理屏可用区域：只裁切非交互系统装饰/输入法的贴边覆盖，不贴边的内部覆盖整窗拒绝坐标。
   *
   * <p>P1-1/P1-2：先收集全部更高层 cover 再统一计算。裁切量只取决于目标原始 bounds 与 cover
   * 几何，与 {@code getWindowsOnAllDisplays()} 的返回顺序/层级无关；旧实现边遍历边改 content，
   * 侧滑条 layer 高于系统栏时会先落进整窗拒绝分支（同一形状改 layer 结果翻转）。
   *
   * <p>边缘判定：与目标边相差 ≤ {@link #EDGE_TOLERANCE_PX} 视为贴边，并且沿边覆盖
   * ≥ {@link #COMPLETE_EDGE_COVERAGE_PERCENT}% 或向内延伸不超过目标垂直方向的
   * 1/{@link #LOCAL_EDGE_DEPTH_DIVISOR}。裁切总是把该边推到 cover 的远侧，剩余区域必然与
   * 该 cover 无交集；若贴边装饰把目标整个盖满，仍按整窗拒绝（{@code obscured=true}）。
   * 内部覆盖（不贴任何边或延伸过深）保持整窗拒绝，不引入部分遮挡降级。
   *
   * <p>P2-1/#212：零面积/无效 bounds 与 ≤1×1 平台字形窗在几何上不可能覆盖像素，直接跳过，
   * 不进排除表、不参与遮挡判定。
   */
  public static ScreenWindowRegion region(int selected, List<Window> windows) {
    Window target = windows.get(selected);
    var original = target.bounds;
    if (original == null || !original.valid()) return null;
    var exclusions = new java.util.ArrayList<ScreenWindowRegion.Exclusion>();
    var chrome = new java.util.ArrayList<Window>();
    var interior = new java.util.ArrayList<Window>();
    for (Window other : windows) {
      if (other.display != 0 || other.id == target.id || other.layer <= target.layer) continue;
      var cover = other.bounds;
      if (cover == null || !cover.valid() || degenerate(cover)) continue;
      if (!original.intersects(cover)) continue;
      if (other.ownedExclusion != null && cover.equals(other.ownedExclusion.bounds())) {
        exclusions.add(other.ownedExclusion);
        continue;
      }
      boolean edgeChrome =
          other.kind == Kind.INPUT_METHOD
              || other.kind == Kind.SYSTEM && !other.focused && !other.active;
      (edgeChrome ? chrome : interior).add(other);
    }
    int left = original.left(), top = original.top();
    int right = original.right(), bottom = original.bottom();
    var clipped = new java.util.ArrayList<Window>();
    for (Window coverWindow : chrome) {
      var cover = coverWindow.bounds;
      Edge edge = bestEdge(cover, original);
      if (edge == null) {
        interior.add(coverWindow);
        continue;
      }
      switch (edge) {
        case TOP -> {
          if (cover.bottom() > top) {
            top = cover.bottom();
            clipped.add(coverWindow);
          }
        }
        case BOTTOM -> {
          if (cover.top() < bottom) {
            bottom = cover.top();
            clipped.add(coverWindow);
          }
        }
        case LEFT -> {
          if (cover.right() > left) {
            left = cover.right();
            clipped.add(coverWindow);
          }
        }
        case RIGHT -> {
          if (cover.left() < right) {
            right = cover.left();
            clipped.add(coverWindow);
          }
        }
      }
    }
    var content = new ScreenWindowRegion.Bounds(left, top, right, bottom);
    var occluders = new java.util.ArrayList<ScreenWindowRegion.Occluder>();
    boolean obscured = false;
    if (!content.valid()) {
      // 贴边装饰把目标整个吃光：content 退回窗口自身，保持可解释并整窗拒绝坐标。
      content = original;
      obscured = true;
      for (Window coverWindow : clipped) occluders.add(occluder(coverWindow));
    } else {
      for (Window other : interior) {
        if (!content.intersects(other.bounds)) continue;
        obscured = true;
        occluders.add(occluder(other));
      }
    }
    return new ScreenWindowRegion(original, content, obscured, exclusions, occluders);
  }

  /** 边界有效但宽高都 ≤1 的平台窗口（#212 的 1×1 字形/角标窗）不覆盖任何可见像素。 */
  private static boolean degenerate(ScreenWindowRegion.Bounds bounds) {
    return bounds.right() - bounds.left() <= 1 && bounds.bottom() - bounds.top() <= 1;
  }

  private static ScreenWindowRegion.Occluder occluder(Window window) {
    return new ScreenWindowRegion.Occluder(window.id(), window.kind.name(), window.bounds());
  }

  /** 选择该 cover 最浅的合法贴边；深度相同按枚举顺序稳定选择。 */
  private static Edge bestEdge(ScreenWindowRegion.Bounds cover, ScreenWindowRegion.Bounds target) {
    Edge best = null;
    long bestDepth = Long.MAX_VALUE;
    for (Edge edge : Edge.values()) {
      long depth = attachedDepth(edge, cover, target);
      if (depth < 0 || !edgeDecoration(edge, cover, target, depth)) continue;
      if (depth < bestDepth) {
        best = edge;
        bestDepth = depth;
      }
    }
    return best;
  }

  /** 贴边窗口向内延伸到目标的深度；未贴边返回 -1。 */
  private static long attachedDepth(
      Edge edge, ScreenWindowRegion.Bounds cover, ScreenWindowRegion.Bounds target) {
    return switch (edge) {
      case TOP ->
          cover.top() <= target.top() + EDGE_TOLERANCE_PX
              ? (long) cover.bottom() - target.top()
              : -1;
      case BOTTOM ->
          cover.bottom() >= target.bottom() - EDGE_TOLERANCE_PX
              ? (long) target.bottom() - cover.top()
              : -1;
      case LEFT ->
          cover.left() <= target.left() + EDGE_TOLERANCE_PX
              ? (long) cover.right() - target.left()
              : -1;
      case RIGHT ->
          cover.right() >= target.right() - EDGE_TOLERANCE_PX
              ? (long) target.right() - cover.left()
              : -1;
    };
  }

  /** 贴边窗是否像该边的装饰：沿边覆盖 ≥80%，或只是贴边的窄条/小浮层。 */
  private static boolean edgeDecoration(
      Edge edge, ScreenWindowRegion.Bounds cover, ScreenWindowRegion.Bounds target, long depth) {
    long covered, span, perpendicular;
    switch (edge) {
      case TOP, BOTTOM -> {
        covered = overlap(cover.left(), cover.right(), target.left(), target.right());
        span = (long) target.right() - target.left();
        perpendicular = (long) target.bottom() - target.top();
      }
      default -> {
        covered = overlap(cover.top(), cover.bottom(), target.top(), target.bottom());
        span = (long) target.bottom() - target.top();
        perpendicular = (long) target.right() - target.left();
      }
    }
    return covered * 100 >= span * COMPLETE_EDGE_COVERAGE_PERCENT
        || depth * LOCAL_EDGE_DEPTH_DIVISOR <= perpendicular;
  }

  private static long overlap(int firstStart, int firstEnd, int secondStart, int secondEnd) {
    return Math.max(0, (long) Math.min(firstEnd, secondEnd) - Math.max(firstStart, secondStart));
  }
}
