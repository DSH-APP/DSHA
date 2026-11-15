package com.deepseekharness.app.screen;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** One running read plus one latest pending event; no replacement of a blocked platform owner. */
public final class AccessibilityReadDispatcher {
  private final Executor worker;
  private Work pending;
  private boolean draining;

  private record Work(BooleanSupplier current, Runnable read) {}

  public AccessibilityReadDispatcher() {
    this(
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "accessibility-pairing-read");
              thread.setDaemon(true);
              return thread;
            }));
  }

  AccessibilityReadDispatcher(Executor worker) {
    this.worker = worker;
  }

  public void submit(BooleanSupplier current, Runnable read) {
    synchronized (this) {
      pending = new Work(current, read);
      if (draining) return;
      draining = true;
    }
    try {
      worker.execute(this::drain);
    } catch (RuntimeException unavailable) {
      synchronized (this) {
        draining = false;
        pending = null;
      }
    }
  }

  private void drain() {
    while (true) {
      Work work;
      synchronized (this) {
        work = pending;
        pending = null;
        if (work == null) {
          draining = false;
          return;
        }
      }
      try {
        if (work.current().getAsBoolean()) work.read().run();
      } catch (RuntimeException unavailable) {
        /* A stale or rejected read cannot kill later watches. */
      }
    }
  }
}
