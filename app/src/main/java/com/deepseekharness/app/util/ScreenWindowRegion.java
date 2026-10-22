package com.deepseekharness.app.util;

/** Immutable screen coordinates observed with one window; never inferred from a later image. */
public record ScreenWindowRegion(Bounds window, Bounds content, boolean obscured) {
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
    return !obscured && content.contains(x1, y1, radius) && content.contains(x2, y2, radius);
  }
}
