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

  @Test
  public void reportableKeepsLabelledOrActionableNodesWithUsableRects() {
    assertTrue(AccessibilityDumpBounds.reportable(true, false, 70, 139, 1165, 139));
    assertTrue(AccessibilityDumpBounds.reportable(false, true, 0, 370, 0, 499));
    assertTrue(AccessibilityDumpBounds.reportable(true, true, 4, 4, 4, 4));
  }

  @Test
  public void reportableDropsReversedRectsEvenWhenTheNodeIsActionable() {
    // issue #143：/app/vscreen/tree 在设置页只回一个可点击节点，bounds 是 (0,1832,0,1792) ——
    // right == left 且 bottom < top，拿它去点屏幕只会点到别处，所以宁可一个节点都不报。
    assertFalse(AccessibilityDumpBounds.reportable(false, true, 0, 1832, 0, 1792));
    assertFalse(AccessibilityDumpBounds.reportable(true, true, 2207, 515, 1240, 572));
  }

  @Test
  public void reportableDropsNodesWithNothingToReport() {
    assertFalse(AccessibilityDumpBounds.reportable(false, false, 0, 0, 100, 200));
  }
}
