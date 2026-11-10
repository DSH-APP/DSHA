package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.InfoSurfacePolicy.Kind;
import com.deepseekharness.app.util.InfoSurfacePolicy.Placement;
import org.junit.Test;

public class InfoSurfacePolicyTest {
  @Test
  public void tabletsAndFoldInnerScreensUseAnchoredPopover() {
    assertEquals(Kind.POPOVER, InfoSurfacePolicy.kind(640, 1024, true));
    assertEquals(Kind.POPOVER, InfoSurfacePolicy.kind(1024, 640, true));
    assertEquals(Kind.POPOVER, InfoSurfacePolicy.kind(673, 841, true));
  }

  @Test
  public void phonePortraitAndNarrowSplitUseSheet() {
    assertEquals(Kind.SHEET, InfoSurfacePolicy.kind(393, 852, true));
    assertEquals(Kind.SHEET, InfoSurfacePolicy.kind(507, 640, true));
    assertEquals(Kind.SHEET, InfoSurfacePolicy.kind(393, 852, false));
  }

  @Test
  public void shortWindowsUseCompactDialogEvenWhenWide() {
    assertEquals(Kind.COMPACT_DIALOG, InfoSurfacePolicy.kind(852, 393, true));
    assertEquals(Kind.COMPACT_DIALOG, InfoSurfacePolicy.kind(1280, 400, true));
    assertEquals(Kind.COMPACT_DIALOG, InfoSurfacePolicy.kind(400, 400, false));
  }

  @Test
  public void boundariesAreInclusiveAt600And480() {
    assertEquals(Kind.SHEET, InfoSurfacePolicy.kind(599, 900, true));
    assertEquals(Kind.POPOVER, InfoSurfacePolicy.kind(600, 900, true));
    assertEquals(Kind.COMPACT_DIALOG, InfoSurfacePolicy.kind(900, 479, true));
    assertEquals(Kind.POPOVER, InfoSurfacePolicy.kind(900, 480, true));
  }

  @Test
  public void wideWithoutAnchorFallsBackToCenteredDialog() {
    assertEquals(Kind.DIALOG, InfoSurfacePolicy.kind(640, 1024, false));
    assertEquals(440, InfoSurfacePolicy.width(Kind.DIALOG, 640));
    assertEquals(361, InfoSurfacePolicy.width(Kind.DIALOG, 393));
    assertEquals(976, InfoSurfacePolicy.maxHeight(Kind.DIALOG, 1024));
  }

  @Test
  public void widthsAndHeightsMatchPrototype() {
    assertEquals(360, InfoSurfacePolicy.width(Kind.POPOVER, 640));
    assertEquals(296, InfoSurfacePolicy.width(Kind.POPOVER, 320));
    assertEquals(420, InfoSurfacePolicy.maxHeight(Kind.POPOVER, 1024));
    assertEquals(560, InfoSurfacePolicy.width(Kind.COMPACT_DIALOG, 852));
    assertEquals(361, InfoSurfacePolicy.maxHeight(Kind.COMPACT_DIALOG, 393));
    assertEquals(393, InfoSurfacePolicy.width(Kind.SHEET, 393));
    assertEquals(511, InfoSurfacePolicy.maxHeight(Kind.SHEET, 852));
  }

  @Test
  public void degenerateSizesNeverGoNegative() {
    for (Kind kind : Kind.values()) {
      assertTrue(InfoSurfacePolicy.width(kind, 0) >= 0);
      assertTrue(InfoSurfacePolicy.maxHeight(kind, -5) >= 0);
    }
  }

  @Test
  public void popoverSitsBelowIconAndHugsRightEdge() {
    // 平板竖屏 640 宽：右上角 ⓘ 位于 [604,108]-[620,124]。
    Placement p = InfoSurfacePolicy.place(640, 1000, 604, 108, 620, 124, 360, 113);
    assertTrue(p.below);
    assertEquals(132, p.top);
    assertEquals(640 - 12 - 360, p.left);
    // 图标中心 612 已贴近右边，小三角夹到圆角内侧。
    assertEquals(360 - InfoSurfacePolicy.CARET_INSET, p.caretX);
    assertEquals(420, p.maxHeight);
  }

  @Test
  public void popoverFlipsAboveWhenBelowIsTooShort() {
    Placement p = InfoSurfacePolicy.place(1024, 600, 300, 500, 316, 516, 360, 200);
    assertFalse(p.below);
    assertEquals(500 - 8 - 200, p.top);
    assertEquals(InfoSurfacePolicy.POPOVER_MAX_HEIGHT, p.maxHeight);
  }

  @Test
  public void popoverStaysBelowAndScrollsWhenBelowHasMoreRoom() {
    Placement p = InfoSurfacePolicy.place(1024, 500, 900, 140, 916, 156, 360, 900);
    assertTrue(p.below);
    assertEquals(500 - 12 - 164, p.maxHeight);
    assertEquals(164, p.top);
    assertTrue(p.top + p.maxHeight <= 500 - 12);
  }

  @Test
  public void caretAvoidsRoundedCornersAtScreenEdges() {
    Placement left = InfoSurfacePolicy.place(800, 900, 0, 100, 16, 116, 360, 100);
    assertEquals(12, left.left);
    assertEquals(InfoSurfacePolicy.CARET_INSET, left.caretX);
    Placement right = InfoSurfacePolicy.place(800, 900, 790, 100, 800, 116, 360, 100);
    assertEquals(360 - InfoSurfacePolicy.CARET_INSET, right.caretX);
  }
}
