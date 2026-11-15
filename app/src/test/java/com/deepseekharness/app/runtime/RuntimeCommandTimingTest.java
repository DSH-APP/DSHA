package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class RuntimeCommandTimingTest {
  @Test
  public void stagesUseOneMonotonicClockAndIndependentDurations() {
    AtomicLong clock = new AtomicLong(5_000_000_000L);
    List<String> lines = new ArrayList<>();
    RuntimeCommandTiming timing = new RuntimeCommandTiming(lines::add, clock::get);
    clock.addAndGet(12_000_000);
    timing.mark("native-files");
    clock.addAndGet(8_000_000);
    timing.mark("launcher-prepare");
    assertEquals(
        List.of(
            "CAS_HOST_TIMING: phase=native-files durationMs=12 elapsedMs=12",
            "CAS_HOST_TIMING: phase=launcher-prepare durationMs=8 elapsedMs=20"),
        lines);
  }

  @Test
  public void brokenDiagnosticConsumerDoesNotInterruptExecutionOrHideOriginalFailure() {
    AtomicLong clock = new AtomicLong();
    RuntimeCommandTiming timing =
        new RuntimeCommandTiming(
            line -> {
              throw new IllegalStateException("diagnostic writer unavailable");
            },
            clock::get);
    clock.set(1_000_000);
    timing.mark("command-failed");
    clock.set(2_000_000);
    timing.mark("exit-finalize");
  }
}
