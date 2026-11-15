package com.deepseekharness.app.util;

/** Immutable screen coordinates observed with one window; never inferred from a later image. */
public record ScreenWindowRegion(
    Bounds window,
    Bounds content,
    boolean obscured,
    java.util.List<Exclusion> exclusions,
    java.util.List<Occluder> occluders) {
  public record Exclusion(int windowId, long epoch, Bounds bounds) {}

  /** Geometry only; no package, label, node text or screenshot content. */
  public record Occluder(int windowId, String kind, Bounds bounds) {}

  public ScreenWindowRegion(Bounds window, Bounds content, boolean obscured) {
    this(window, content, obscured, java.util.List.of(), java.util.List.of());
  }

  public ScreenWindowRegion(
      Bounds window, Bounds content, boolean obscured, java.util.List<Exclusion> exclusions) {
    this(window, content, obscured, exclusions, java.util.List.of());
  }

  public ScreenWindowRegion {
    exclusions = java.util.List.copyOf(exclusions);
    occluders = java.util.List.copyOf(occluders);
  }

  public record Bounds(int left, int top, int right, int bottom) {
    public boolean valid() {
      return left < right && top < bottom;
    }

    public boolean within(int width, int height) {
      return valid() && left >= 0 && top >= 0 && right <= width && bottom <= height;
    }

    public boolean intersects(Bounds other) {
      return valid()
          && other.valid()
          && left < other.right
          && other.left < right
          && top < other.bottom
          && other.top < bottom;
    }

    public boolean contains(int x, int y, int radius) {
      return radius >= 0
          && (long) x - radius >= left
          && (long) y - radius >= top
          && (long) x + radius < right
          && (long) y + radius < bottom;
    }
  }

  public static ScreenWindowRegion full(int width, int height) {
    Bounds bounds = new Bounds(0, 0, width, height);
    return new ScreenWindowRegion(bounds, bounds, false);
  }

  public boolean within(int width, int height) {
    return window != null
        && content != null
        && window.valid()
        && content.within(width, height)
        && content.left >= window.left
        && content.top >= window.top
        && content.right <= window.right
        && content.bottom <= window.bottom;
  }

  /** A straight injected path and its one-pixel boundary reserve fit a convex rectangle. */
  public boolean containsTrajectory(int x1, int y1, int x2, int y2, int radius) {
    if (obscured || !content.contains(x1, y1, radius) || !content.contains(x2, y2, radius))
      return false;
    for (Exclusion exclusion : exclusions)
      if (crosses(exclusion.bounds, x1, y1, x2, y2, radius)) return false;
    return true;
  }

  public boolean overlapsExclusion(Bounds bounds) {
    for (Exclusion exclusion : exclusions) if (exclusion.bounds.intersects(bounds)) return true;
    return false;
  }

  /** Semantic scrolling has no touch point. Require visible target area outside owned controls. */
  public boolean hasUnexcludedArea(Bounds bounds) {
    if (obscured || bounds == null || !bounds.valid() || !content.intersects(bounds)) return false;
    var remaining = new java.util.ArrayList<Bounds>();
    remaining.add(
        new Bounds(
            Math.max(content.left, bounds.left),
            Math.max(content.top, bounds.top),
            Math.min(content.right, bounds.right),
            Math.min(content.bottom, bounds.bottom)));
    for (Exclusion exclusion : exclusions) {
      var next = new java.util.ArrayList<Bounds>();
      for (Bounds part : remaining) {
        Bounds cover = exclusion.bounds;
        if (!part.intersects(cover)) {
          next.add(part);
          continue;
        }
        if (part.top < cover.top) next.add(new Bounds(part.left, part.top, part.right, cover.top));
        if (part.bottom > cover.bottom)
          next.add(new Bounds(part.left, cover.bottom, part.right, part.bottom));
        int top = Math.max(part.top, cover.top), bottom = Math.min(part.bottom, cover.bottom);
        if (part.left < cover.left) next.add(new Bounds(part.left, top, cover.left, bottom));
        if (part.right > cover.right) next.add(new Bounds(cover.right, top, part.right, bottom));
      }
      remaining = next;
      if (remaining.isEmpty()) return false;
    }
    return !remaining.isEmpty();
  }

  /** Liang-Barsky clipping against the closed exclusion plus the touch boundary reserve. */
  private static boolean crosses(Bounds b, int x1, int y1, int x2, int y2, int radius) {
    double dx = (double) x2 - x1, dy = (double) y2 - y1;
    double[] p = {-dx, dx, -dy, dy};
    double[] q = {
      (double) x1 - b.left + radius,
      (double) b.right - x1 + radius,
      (double) y1 - b.top + radius,
      (double) b.bottom - y1 + radius
    };
    double lo = 0, hi = 1;
    for (int i = 0; i < 4; i++) {
      if (p[i] == 0) {
        if (q[i] < 0) return false;
      } else {
        double t = q[i] / p[i];
        if (p[i] < 0) lo = Math.max(lo, t);
        else hi = Math.min(hi, t);
        if (lo > hi) return false;
      }
    }
    return true;
  }
}
