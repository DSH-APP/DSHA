package com.deepseekharness.app.screen;

import com.deepseekharness.app.util.AccessibilityTreeBudget;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Cooperative expansion budget; it cannot interrupt a Binder call already executing. */
public final class ScreenReadBudget {
  public static final long EXPANSION_MILLIS = 800;
  private final AccessibilityTreeBudget nodes = new AccessibilityTreeBudget();
  private final LongSupplier clock;
  private final BooleanSupplier current;
  private final long started;
  private boolean stopped, timedOut;

  public ScreenReadBudget(LongSupplier clock, BooleanSupplier current) {
    this.clock = clock;
    this.current = current;
    started = clock.getAsLong();
  }

  private boolean allowed() {
    if (!current.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new IllegalStateException("SCREEN_RUN_CHANGED");
    long elapsed = clock.getAsLong() - started;
    if (elapsed < 0 || elapsed >= EXPANSION_MILLIS) timedOut = stopped = true;
    return !stopped;
  }

  public boolean enter(int depth, int outputCount) {
    if (!allowed()) return false;
    if (nodes.enter(depth, outputCount)) return true;
    stopped = true;
    return false;
  }

  public boolean childSlot(int outputCount) {
    if (!allowed()) return false;
    if (nodes.childSlot(outputCount)) return true;
    stopped = true;
    return false;
  }

  public int visits() {
    return nodes.visits();
  }

  public boolean exhausted() {
    return stopped || nodes.exhausted();
  }

  public boolean timedOut() {
    return timedOut;
  }
}
