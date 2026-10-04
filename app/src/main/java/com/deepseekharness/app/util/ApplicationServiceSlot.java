package com.deepseekharness.app.util;

/** One application service reference; a destroyed predecessor cannot detach its replacement. */
public final class ApplicationServiceSlot<T> {
  private T value;

  public synchronized void attach(T service) {
    value = java.util.Objects.requireNonNull(service);
  }

  public synchronized T current() {
    return value;
  }

  public synchronized void release(T service) {
    if (value == service) value = null;
  }
}
