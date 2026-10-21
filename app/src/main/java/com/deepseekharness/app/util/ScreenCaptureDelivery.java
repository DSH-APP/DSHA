package com.deepseekharness.app.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 异步截图的单一所有权交接：超时/撤销后的迟到资源直接释放。 */
public final class ScreenCaptureDelivery<T> implements AutoCloseable {
  private final BooleanSupplier current;
  private final Consumer<T> release;
  private final Consumer<Throwable> lateFailure;
  private final CountDownLatch ready = new CountDownLatch(1);
  private boolean closed;
  private T value;
  private String error = "SCREENSHOT_NO_RESULT";
  private Throwable failure;
  private boolean failureTaken;
  private boolean lateFailureReported;

  public ScreenCaptureDelivery(BooleanSupplier current, Consumer<T> release) {
    this(current, release, ignored -> {});
  }

  public ScreenCaptureDelivery(
      BooleanSupplier current, Consumer<T> release, Consumer<Throwable> lateFailure) {
    this.current = current;
    this.release = release;
    this.lateFailure = lateFailure;
  }

  public void complete(T result) {
    boolean retain;
    synchronized (this) {
      retain = !closed && value == null && current.getAsBoolean();
      if (retain) value = result;
      ready.countDown();
    }
    if (!retain) release.accept(result);
  }

  public void fail(String code) {
    fail(code, null);
  }

  /** The waiter owns a timely cause; after close a late cause has one diagnostic owner. */
  public void fail(String code, Throwable cause) {
    Throwable report = null;
    synchronized (this) {
      error = code;
      if (cause != null) {
        if (!closed && !failureTaken && failure == null) failure = cause;
        else if ((closed || failureTaken) && cause != failure && !lateFailureReported) {
          lateFailureReported = true;
          report = cause;
        }
      }
      ready.countDown();
    }
    reportLate(report);
  }

  public synchronized Throwable takeFailure() {
    if (failureTaken) return null;
    failureTaken = true;
    return failure;
  }

  private void reportLate(Throwable report) {
    if (report == null) return;
    try {
      lateFailure.accept(report);
    } catch (Throwable diagnosticUnavailable) {
      // Optional diagnostics cannot change delivery, resource release or the request result.
    }
  }

  public T await(long millis) throws InterruptedException {
    if (millis <= 0 || !ready.await(millis, TimeUnit.MILLISECONDS)) {
      synchronized (this) {
        error = "SCREENSHOT_TIMEOUT";
      }
      return null;
    }
    T result;
    synchronized (this) {
      result = value;
      value = null;
      closed = true;
    }
    if (result != null && !current.getAsBoolean()) {
      release.accept(result);
      return null;
    }
    return result;
  }

  public synchronized String error() {
    return error;
  }

  @Override
  public void close() {
    T remaining;
    Throwable report = null;
    synchronized (this) {
      closed = true;
      remaining = value;
      value = null;
      if (!failureTaken && failure != null && !lateFailureReported) {
        failureTaken = true;
        lateFailureReported = true;
        report = failure;
      }
      ready.countDown();
    }
    try {
      if (remaining != null) release.accept(remaining);
    } finally {
      reportLate(report);
    }
  }
}
