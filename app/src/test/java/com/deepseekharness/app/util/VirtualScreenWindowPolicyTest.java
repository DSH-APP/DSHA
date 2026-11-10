package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VirtualScreenWindowPolicyTest {
  @Test
  public void metadataSelectsFocusedWindowBeforeRootReadAndNeverAnotherDisplay() {
    var windows =
        java.util.List.of(
            new VirtualScreenWindowPolicy.Window(0, 1, 100, true, true, true),
            new VirtualScreenWindowPolicy.Window(8, 2, 90, true, false, true),
            new VirtualScreenWindowPolicy.Window(8, 3, 1, true, true, false),
            new VirtualScreenWindowPolicy.Window(8, 4, 110, false, true, true));
    assertEquals(2, VirtualScreenWindowPolicy.select(8, windows));
    assertEquals(0, VirtualScreenWindowPolicy.select(0, windows));
    assertEquals(-1, VirtualScreenWindowPolicy.select(9, windows));
  }

  @Test
  public void ambiguousWindowsAndInvalidIdentityFailClosed() {
    assertEquals(
        -1,
        VirtualScreenWindowPolicy.select(
            8,
            java.util.List.of(
                new VirtualScreenWindowPolicy.Window(8, 2, 1, true, true, false),
                new VirtualScreenWindowPolicy.Window(8, 3, 1, true, true, false))));
    assertEquals(
        -1,
        VirtualScreenWindowPolicy.select(
            8,
            java.util.List.of(new VirtualScreenWindowPolicy.Window(8, -1, 1, true, true, true))));
  }

  @Test
  public void focusedWindowWinsConsistently() {
    assertTrue(
        VirtualScreenWindowPolicy.rank(true, false) > VirtualScreenWindowPolicy.rank(false, true));
    assertTrue(
        VirtualScreenWindowPolicy.rank(false, true) > VirtualScreenWindowPolicy.rank(false, false));
  }

  @Test
  public void approvalCannotFollowAChangedApplicationOrDisplay() {
    assertTrue(VirtualScreenWindowPolicy.approved(8, "app.a", 8, "app.a"));
    assertFalse(VirtualScreenWindowPolicy.approved(8, "app.a", 8, "app.b"));
    assertFalse(VirtualScreenWindowPolicy.approved(8, "app.a", 9, "app.a"));
    assertFalse(VirtualScreenWindowPolicy.approved(8, "", 8, "app.b"));
  }

  @Test
  public void detailedSelectionCountsWindowKindsAndKeepsUniqueChoice() {
    var windows =
        java.util.List.of(
            new VirtualScreenWindowPolicy.Window(
                8, 2, VirtualScreenWindowPolicy.TYPE_APPLICATION, 10, true, true),
            new VirtualScreenWindowPolicy.Window(
                8, 4, VirtualScreenWindowPolicy.TYPE_INPUT_METHOD, 20, false, false),
            new VirtualScreenWindowPolicy.Window(
                8, 5, VirtualScreenWindowPolicy.TYPE_SYSTEM, 30, false, false),
            new VirtualScreenWindowPolicy.Window(
                8, 6, VirtualScreenWindowPolicy.TYPE_APPLICATION, 1, false, true),
            new VirtualScreenWindowPolicy.Window(
                0, 7, VirtualScreenWindowPolicy.TYPE_APPLICATION, 99, true, true),
            new VirtualScreenWindowPolicy.Window(
                8, -1, VirtualScreenWindowPolicy.TYPE_APPLICATION, 99, true, true));
    var selection = VirtualScreenWindowPolicy.selectDetailed(8, windows);
    assertEquals(0, selection.index);
    assertEquals(2, selection.applicationWindows);
    assertEquals(1, selection.inputMethodWindows);
    assertEquals(1, selection.systemWindows);
    assertEquals(2, selection.focusedWindowId);
    assertEquals(2, selection.activeWindowId);
    assertFalse(selection.ambiguous);
    // 旧入口与详细结果必须一致。
    assertEquals(0, VirtualScreenWindowPolicy.select(8, windows));
  }

  @Test
  public void ambiguousApplicationWindowsFailClosedAndAreReported() {
    var selection =
        VirtualScreenWindowPolicy.selectDetailed(
            8,
            java.util.List.of(
                new VirtualScreenWindowPolicy.Window(
                    8, 2, VirtualScreenWindowPolicy.TYPE_APPLICATION, 1, true, false),
                new VirtualScreenWindowPolicy.Window(
                    8, 3, VirtualScreenWindowPolicy.TYPE_APPLICATION, 1, true, false),
                new VirtualScreenWindowPolicy.Window(
                    8, 4, VirtualScreenWindowPolicy.TYPE_INPUT_METHOD, 9, false, true)));
    assertEquals(-1, selection.index);
    assertTrue(selection.ambiguous);
    assertEquals(2, selection.applicationWindows);
    assertEquals(1, selection.inputMethodWindows);
    // 只有系统/输入法窗口时不是歧义，但也没有可选应用窗口。
    var noApplication =
        VirtualScreenWindowPolicy.selectDetailed(
            8,
            java.util.List.of(
                new VirtualScreenWindowPolicy.Window(
                    8, 4, VirtualScreenWindowPolicy.TYPE_INPUT_METHOD, 9, true, true)));
    assertEquals(-1, noApplication.index);
    assertFalse(noApplication.ambiguous);
    assertEquals(0, noApplication.applicationWindows);
  }
}
