package com.deepseekharness.app.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Bounds the response without replacing an Android call which has not actually returned. */
public final class ScreenRequestExecutor {
  public static final long RESPONSE_LIMIT_MILLIS = 15_000;
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(15),
          task -> {
            Thread thread = new Thread(task, "screen-request-owner");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  public static final class Refused extends Exception {
    public final String code;
    public final boolean requestMayStillBeRunning;

    Refused(String code) {
      this(code, false);
    }

    Refused(String code, boolean requestMayStillBeRunning) {
      super(code);
      this.code = code;
      this.requestMayStillBeRunning = requestMayStillBeRunning;
    }
  }

  public <T> T execute(
      BooleanSupplier current,
      LongSupplier clock,
      long deadline,
      Callable<T> action,
      java.util.function.Function<Throwable, T> failureReply)
      throws Refused {
    long remaining = Math.min(RESPONSE_LIMIT_MILLIS, deadline - clock.getAsLong());
    if (remaining <= 0) throw new Refused("SCREEN_RUN_CHANGED");
    var startedWork = new java.util.concurrent.atomic.AtomicBoolean();
    var finishedWork = new java.util.concurrent.atomic.AtomicBoolean();
    FutureTask<T> task =
        new FutureTask<>(
            () -> {
              startedWork.set(true);
              try {
                if (clock.getAsLong() >= deadline || !current.getAsBoolean())
                  throw new Refused("SCREEN_RUN_CHANGED");
                return action.call();
              } catch (Refused refused) {
                throw refused;
              } catch (Throwable failure) {
                // Original technical evidence stays with the actual request worker, so a slow
                // diagnostic sink cannot extend the caller's response deadline either.
                return failureReply.apply(failure);
              } finally {
                finishedWork.set(true);
              }
            });
    long started = System.nanoTime();
    try {
      worker.execute(task);
    } catch (java.util.concurrent.RejectedExecutionException full) {
      throw new Refused("SCREEN_QUEUE_FULL");
    }
    try {
      long budget = TimeUnit.MILLISECONDS.toNanos(remaining) - (System.nanoTime() - started);
      if (budget <= 0) throw new TimeoutException();
      return task.get(budget, TimeUnit.NANOSECONDS);
    } catch (TimeoutException timeout) {
      // cancel(false) does not pretend that a synchronous platform call has exited.
      task.cancel(false);
      worker.remove(task); // Only queued work is removable; the running owner is never replaced.
      throw new Refused("SCREEN_REQUEST_TIMEOUT", startedWork.get() && !finishedWork.get());
    } catch (InterruptedException interrupted) {
      task.cancel(false);
      worker.remove(task);
      Thread.currentThread().interrupt();
      throw new Refused("SCREEN_REQUEST_CANCELLED", startedWork.get() && !finishedWork.get());
    } catch (ExecutionException failure) {
      Throwable original = failure.getCause();
      if (original instanceof Refused refused) throw refused;
      if (original instanceof RuntimeException error) throw error;
      if (original instanceof Error error) throw error;
      throw new IllegalStateException(original);
    }
  }
}
