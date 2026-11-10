package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class RuntimeToolsTimingTest {
  @Test
  public void ordinaryFastPreparationDoesNotEmitAnyLog() {
    AtomicLong clock = new AtomicLong();
    RuntimeToolsTiming timing = new RuntimeToolsTiming(clock::get);
    clock.set(49_999_999L);
    timing.finish(value -> fail("fast preparation must stay quiet"));
  }

  @Test
  public void slowQueryIsSeparatedFromLockWaitAndUnexecutedFullPreparation() {
    AtomicLong clock = new AtomicLong();
    RuntimeToolsTiming timing = new RuntimeToolsTiming(clock::get);
    long[] times = {1, 3, 253, 258, 260, 263};
    for (int at = 0; at < times.length; at++) {
      clock.set(times[at] * 1_000_000);
      timing.mark(at);
    }
    clock.set(264_000_000);
    var lines = new ArrayList<String>();
    timing.finish(lines::add);
    assertEquals(1, lines.size());
    assertEquals(
        "RUNTIME_TOOLS_TIMING: totalMs=264 rootMs=1 network-setupMs=2 dns-queryMs=250 settings-and-lockMs=5 resolver-filesMs=2 reuse-checkMs=3 full-prepareMs=-1 tailMs=1",
        lines.get(0));
  }

  @Test
  public void earlyFailureHasUnexecutedPhasesAndLoggerFailureCannotEscape() {
    AtomicLong clock = new AtomicLong();
    RuntimeToolsTiming timing = new RuntimeToolsTiming(clock::get);
    clock.set(60_000_000);
    var lines = new ArrayList<String>();
    timing.finish(lines::add);
    assertTrue(lines.get(0).contains("rootMs=-1"));
    assertTrue(lines.get(0).endsWith("tailMs=60"));
    timing.finish(
        value -> {
          throw new IllegalStateException("logger");
        });
  }
}
