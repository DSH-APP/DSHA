package com.deepseekharness.app.core;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Monotonic preparation phases only; these observations never authorize launch or retry. */
final class StartupPreparationTiming {
  interface Operation<T> {
    T run() throws Exception;
  }

  private final Consumer<String> output;
  private final LongSupplier nanos;
  private final long started;

  StartupPreparationTiming(Consumer<String> output) {
    this(output, System::nanoTime);
  }

  StartupPreparationTiming(Consumer<String> output, LongSupplier nanos) {
    this.output = Objects.requireNonNull(output);
    this.nanos = Objects.requireNonNull(nanos);
    started = nanos.getAsLong();
  }

  <T> T run(String phase, Operation<T> operation) throws Exception {
    if (!phase.matches("[a-z][a-z-]{0,47}"))
      throw new IllegalArgumentException("STARTUP_TIMING_PHASE");
    long before = nanos.getAsLong();
    boolean completed = false;
    try {
      T result = operation.run();
      completed = true;
      return result;
    } finally {
      long now = nanos.getAsLong();
      try {
        output.accept(
            "STARTUP_PREP_TIMING: phase="
                + phase
                + " durationMs="
                + Math.max(0, now - before) / 1_000_000
                + " elapsedMs="
                + Math.max(0, now - started) / 1_000_000
                + " outcome="
                + (completed ? "returned" : "threw"));
      } catch (RuntimeException ignored) {
        // Diagnostic output must not replace an actual operation result or its original failure.
      }
    }
  }
}
