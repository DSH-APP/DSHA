package com.deepseekharness.app.util;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** One encoded image response at a time, including its socket write. */
public final class BridgeImageBudget {
  private static final Semaphore PERMIT = new Semaphore(1);

  private BridgeImageBudget() {}

  public static Lease tryAcquire() {
    return PERMIT.tryAcquire() ? new Lease() : null;
  }

  public static final class Lease implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();

    private Lease() {}

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) PERMIT.release();
    }
  }
}
