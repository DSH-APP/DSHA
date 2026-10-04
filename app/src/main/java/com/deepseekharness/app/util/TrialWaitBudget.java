package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.function.LongSupplier;

/** 隔离维护校验的单调时钟预算；普通 Web 启动仍无强制截止时间。 */
public final class TrialWaitBudget {
  public enum Phase {
    STARTING(600_000),
    AUTHENTICATING(120_000),
    RENDERING(300_000),
    SETTINGS(600_000);
    final long limitMs;

    Phase(long limitMs) {
      this.limitMs = limitMs;
    }
  }

  private final LongSupplier clockMs;
  private final Phase phase;
  private final long started;

  public TrialWaitBudget(Phase phase) {
    this(phase, () -> System.nanoTime() / 1_000_000);
  }

  public TrialWaitBudget(Phase phase, LongSupplier clockMs) {
    this.phase = java.util.Objects.requireNonNull(phase);
    this.clockMs = java.util.Objects.requireNonNull(clockMs);
    started = clockMs.getAsLong();
  }

  public void check() throws IOException {
    if (elapsedMs() >= phase.limitMs) throw new IOException("TRIAL_TIMEOUT:" + phase.name());
  }

  public long elapsedMs() {
    return Math.max(0, clockMs.getAsLong() - started);
  }

  public boolean stillWaiting() {
    return elapsedMs() >= 60_000;
  }
}
