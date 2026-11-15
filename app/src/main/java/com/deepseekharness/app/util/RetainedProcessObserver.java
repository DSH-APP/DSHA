package com.deepseekharness.app.util;

import java.util.function.Consumer;

/** Read-only exit observation of one retained Process; transient failures never retire its owner. */
public final class RetainedProcessObserver {
  public enum State {
    RUNNING,
    UNKNOWN
  }

  @FunctionalInterface
  interface Pause {
    void sleep(long millis) throws InterruptedException;
  }

  private RetainedProcessObserver() {}

  public static void awaitExit(Process process, Consumer<State> observed) {
    awaitExit(process, observed, Thread::sleep);
  }

  static void awaitExit(Process process, Consumer<State> observed, Pause pause) {
    java.util.Objects.requireNonNull(process);
    boolean interrupted = Thread.interrupted();
    State previous = null;
    try {
      while (true) {
        State state;
        try {
          process.exitValue();
          return;
        } catch (IllegalThreadStateException running) {
          state = State.RUNNING;
        } catch (RuntimeException unavailable) {
          state = State.UNKNOWN;
        }
        if (state != previous) {
          previous = state;
          try {
            if (observed != null) observed.accept(state);
          } catch (RuntimeException ignored) {
            // Reporting is not the owner; an unavailable UI/logger cannot end exit observation.
          }
        }
        try {
          pause.sleep(1000);
        } catch (InterruptedException retry) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
