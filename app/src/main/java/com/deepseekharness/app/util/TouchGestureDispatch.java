package com.deepseekharness.app.util;

/** One gesture owns exactly one input route; unknown stream results never become discrete replay. */
public final class TouchGestureDispatch {
  public static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3;

  public interface Sink {
    void stream(int action, float x, float y);

    void input(float x1, float y1, float x2, float y2, int duration, boolean tap);
  }

  private enum Route {
    IDLE,
    STREAM,
    DISCRETE,
    CONSUMED
  }

  private Route route = Route.IDLE;
  private Sink sink;
  private float x, y;

  private static boolean point(float[] value) {
    return value != null
        && value.length == 2
        && !Float.isNaN(value[0])
        && !Float.isNaN(value[1])
        && !Float.isInfinite(value[0])
        && !Float.isInfinite(value[1])
        && value[0] >= 0
        && value[1] >= 0;
  }

  public void down(Sink target, boolean streamAvailable, boolean enabled, float[] start) {
    cancel();
    sink = target;
    route = Route.CONSUMED;
    if (target == null || !enabled || !point(start)) return;
    x = start[0];
    y = start[1];
    route = streamAvailable ? Route.STREAM : Route.DISCRETE;
    if (route == Route.STREAM)
      try {
        target.stream(DOWN, x, y);
      } catch (RuntimeException unknown) {
        cancel();
      }
  }

  public void move(float[] location) {
    if (route != Route.STREAM) return;
    if (!point(location)) {
      cancel();
      return;
    }
    try {
      sink.stream(MOVE, location[0], location[1]);
    } catch (RuntimeException unknown) {
      cancel();
    }
  }

  public boolean up(float[] end, int duration, boolean tap) {
    Route chosen = route;
    Sink target = sink;
    route = Route.IDLE;
    sink = null;
    if (chosen == Route.STREAM) {
      if (!point(end)) {
        try {
          target.stream(CANCEL, 0, 0);
        } catch (RuntimeException unknown) {
        }
        return false;
      }
      try {
        target.stream(UP, end[0], end[1]);
        return true;
      } catch (RuntimeException unknown) {
        try {
          target.stream(CANCEL, 0, 0);
        } catch (RuntimeException unavailable) {
        }
      }
      return false;
    }
    if (chosen == Route.DISCRETE && point(end))
      try {
        target.input(x, y, end[0], end[1], Math.max(50, Math.min(3000, duration)), tap);
        return true;
      } catch (RuntimeException unknown) {
      }
    return false;
  }

  public void cancel() {
    Route chosen = route;
    Sink target = sink;
    route = Route.CONSUMED;
    sink = null;
    if (chosen == Route.STREAM && target != null)
      try {
        target.stream(CANCEL, 0, 0);
      } catch (RuntimeException unknown) {
      }
  }
}
