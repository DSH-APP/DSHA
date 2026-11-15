package com.deepseekharness.app.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartupPreparationTimingTest {
  @Test
  public void reportsSeparateDurationsAndCumulativeMonotonicTimeWithoutOperationContents()
      throws Exception {
    var clock = new AtomicLong(8_000_000);
    var messages = new ArrayList<String>();
    var timing = new StartupPreparationTiming(messages::add, clock::get);
    assertEquals(
        "private result",
        timing.run(
            "register-builtins",
            () -> {
              clock.addAndGet(17_000_000);
              return "private result";
            }));
    clock.addAndGet(3_000_000);
    timing.run(
        "legacy-plugin-reviews",
        () -> {
          clock.addAndGet(5_000_000);
          return null;
        });
    assertEquals(
        List.of(
            "STARTUP_PREP_TIMING: phase=register-builtins durationMs=17 elapsedMs=17 outcome=returned",
            "STARTUP_PREP_TIMING: phase=legacy-plugin-reviews durationMs=5 elapsedMs=25 outcome=returned"),
        messages);
    assertFalse(messages.toString().contains("private result"));
  }

  @Test
  public void diagnosticFailureDoesNotHideTheOriginalOperationFailureOrChangeItsResult()
      throws Exception {
    var timing =
        new StartupPreparationTiming(
            line -> {
              throw new IllegalStateException("observer");
            });
    var failure = new IOException("original failure");
    assertSame(
        failure,
        assertThrows(
            IOException.class,
            () ->
                timing.run(
                    "recover-data",
                    () -> {
                      throw failure;
                    })));
    assertEquals(42, (int) timing.run("runtime-tools", () -> 42));
  }

  @Test
  public void failureIsObservedWithoutBeingConvertedIntoSuccessAndInvalidPhasesNeverRun()
      throws Exception {
    var messages = new ArrayList<String>();
    var timing = new StartupPreparationTiming(messages::add, () -> 0L);
    assertThrows(
        IOException.class,
        () ->
            timing.run(
                "register-builtins",
                () -> {
                  throw new IOException();
                }));
    assertEquals(
        List.of(
            "STARTUP_PREP_TIMING: phase=register-builtins durationMs=0 elapsedMs=0 outcome=threw"),
        messages);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            timing.run(
                "user path /secret",
                () -> {
                  fail();
                  return null;
                }));
  }
}
