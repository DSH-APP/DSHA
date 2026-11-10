package com.deepseekharness.app.runtime;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One bounded diagnostic for a slow preparation; no file, network or durable-log work. */
final class RuntimeToolsTiming {
  private static final String[] PHASES = {
    "root",
    "network-setup",
    "dns-query",
    "settings-and-lock",
    "resolver-files",
    "reuse-check",
    "full-prepare"
  };
  private final LongSupplier nanos;
  private final long started;
  private final long[] completed = new long[PHASES.length];

  RuntimeToolsTiming() {
    this(System::nanoTime);
  }

  RuntimeToolsTiming(LongSupplier nanos) {
    this.nanos = nanos;
    started = nanos.getAsLong();
    java.util.Arrays.fill(completed, -1);
  }

  void mark(int phase) {
    completed[phase] = Math.max(0, nanos.getAsLong() - started);
  }

  void finish(Consumer<String> output) {
    long total = Math.max(0, nanos.getAsLong() - started);
    if (total < 50_000_000L) return;
    StringBuilder line =
        new StringBuilder("RUNTIME_TOOLS_TIMING: totalMs=").append(total / 1_000_000);
    long previous = 0;
    for (int at = 0; at < PHASES.length; at++) {
      long value = completed[at];
      line.append(' ')
          .append(PHASES[at])
          .append("Ms=")
          .append(value < 0 ? -1 : Math.max(0, value - previous) / 1_000_000);
      if (value >= 0) previous = value;
    }
    line.append(" tailMs=").append(Math.max(0, total - previous) / 1_000_000);
    try {
      output.accept(line.toString());
    } catch (RuntimeException diagnosticUnavailable) {
      // A broken logger cannot change completion or the original preparation failure.
    }
  }
}
