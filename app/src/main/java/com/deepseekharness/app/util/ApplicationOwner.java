package com.deepseekharness.app.util;

import java.util.function.Supplier;

/** One lazily constructed service owned by one application, never by an Activity. */
public final class ApplicationOwner<T> {
  private T value;
  private boolean constructing;

  public synchronized T get(Supplier<T> factory) {
    if (value != null) return value;
    if (constructing) throw new IllegalStateException("APPLICATION_OWNER_RECURSION");
    constructing = true;
    try {
      T candidate = factory.get();
      if (candidate == null) throw new IllegalStateException("APPLICATION_OWNER_MISSING");
      value = candidate;
      return candidate;
    } finally {
      constructing = false;
    }
  }
}
