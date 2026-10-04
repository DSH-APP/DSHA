package com.deepseekharness.app.util;

/** Accessibility text can have zero area; only reversed bounds are noise. */
public final class AccessibilityDumpBounds {
  private AccessibilityDumpBounds() {}

  public static boolean ordered(int left, int top, int right, int bottom) {
    return right >= left && bottom >= top;
  }
}
