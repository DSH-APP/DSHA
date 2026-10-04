package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AccessibilityDumpBoundsTest {
  @Test
  public void retainsVisibleAndZeroAreaText() {
    assertTrue(AccessibilityDumpBounds.ordered(70, 139, 1165, 139));
    assertTrue(AccessibilityDumpBounds.ordered(0, 370, 0, 499));
    assertTrue(AccessibilityDumpBounds.ordered(0, 0, 100, 200));
    assertTrue(AccessibilityDumpBounds.ordered(4, 4, 4, 4));
  }

  @Test
  public void rejectsReversedAxesWithoutOverflow() {
    assertFalse(AccessibilityDumpBounds.ordered(2207, 515, 1240, 572));
    assertFalse(AccessibilityDumpBounds.ordered(1, 20, 4, 10));
    assertFalse(AccessibilityDumpBounds.ordered(Integer.MAX_VALUE, 0, Integer.MIN_VALUE, 1));
    assertTrue(AccessibilityDumpBounds.ordered(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 1));
  }
}
