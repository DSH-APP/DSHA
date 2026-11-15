package com.deepseekharness.app.runtime;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 单次受控命令的宿主分段时钟；不记录命令、路径或用户内容，也不影响执行结果。 */
final class RuntimeCommandTiming {
  private final Consumer<String> output;
  private final LongSupplier nanos;
  private final long started;
  private long previous;

  RuntimeCommandTiming(Consumer<String> output) {
    this(output, System::nanoTime);
  }

  RuntimeCommandTiming(Consumer<String> output, LongSupplier nanos) {
    this.output = java.util.Objects.requireNonNull(output);
    this.nanos = java.util.Objects.requireNonNull(nanos);
    started = previous = nanos.getAsLong();
  }

  void mark(String phase) {
    long now = nanos.getAsLong();
    long elapsed = Math.max(0, now - started) / 1_000_000;
    long duration = Math.max(0, now - previous) / 1_000_000;
    previous = now;
    try {
      output.accept(
          "CAS_HOST_TIMING: phase=" + phase + " durationMs=" + duration + " elapsedMs=" + elapsed);
    } catch (RuntimeException ignored) {
      // 诊断收集不可改变事务、进程退出判定或原有异常。
    }
  }
}
