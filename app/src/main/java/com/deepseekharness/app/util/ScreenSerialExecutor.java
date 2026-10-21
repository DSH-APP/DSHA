package com.deepseekharness.app.util;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 有界串行主屏请求；撤销不争用此执行锁。等待到期的请求不开始执行。 */
public final class ScreenSerialExecutor {
  private final Semaphore capacity = new Semaphore(16, true);
  private final ReentrantLock actions = new ReentrantLock(true);

  public static final class Refused extends Exception {
    public final String code;

    public Refused(String code) {
      super(code);
      this.code = code;
    }
  }

  public <T> T execute(
      BooleanSupplier current, LongSupplier clock, long deadline, Callable<T> action)
      throws Exception {
    if (!capacity.tryAcquire()) throw new Refused("SCREEN_QUEUE_FULL");
    boolean locked = false;
    try {
      long remaining = deadline - clock.getAsLong();
      if (remaining <= 0 || !current.getAsBoolean()) throw new Refused("SCREEN_RUN_CHANGED");
      locked = actions.tryLock(Math.min(remaining, 30_000L), TimeUnit.MILLISECONDS);
      if (!locked) throw new Refused("SCREEN_QUEUE_TIMEOUT");
      if (clock.getAsLong() >= deadline || !current.getAsBoolean())
        throw new Refused("SCREEN_RUN_CHANGED");
      return action.call();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new Refused("SCREEN_REQUEST_CANCELLED");
    } finally {
      if (locked) actions.unlock();
      capacity.release();
    }
  }
}
