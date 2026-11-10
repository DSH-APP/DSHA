package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class OverlayPlacementTest {
  @Test
  public void smallMovementStaysATap() {
    assertFalse(OverlayPlacement.isDrag(3, 4, 8));
    assertFalse(OverlayPlacement.isDrag(0, 8, 8));
    assertTrue(OverlayPlacement.isDrag(0, 9, 8));
    assertTrue(OverlayPlacement.isDrag(-6, -6, 8));
  }

  @Test
  public void bottomEndAnchorInvertsFingerMovement() {
    // 手指向左 100、向上 50：距右边、下边的偏移都增大。
    assertEquals(130, OverlayPlacement.offset(30, 900, 800));
    assertEquals(74, OverlayPlacement.offset(24, 1500, 1450));
    // 向右、向下则减小。
    assertEquals(10, OverlayPlacement.offset(30, 500, 520));
  }

  @Test
  public void offsetIsClampedOnScreen() {
    assertEquals(12, OverlayPlacement.clamp(-40, 200, 1000, 12));
    assertEquals(500, OverlayPlacement.clamp(500, 200, 1000, 12));
    assertEquals(788, OverlayPlacement.clamp(5000, 200, 1000, 12));
    // 窗口比屏幕还大：贴在锚点一侧，不给负偏移。
    assertEquals(12, OverlayPlacement.clamp(300, 1200, 1000, 12));
  }
}
