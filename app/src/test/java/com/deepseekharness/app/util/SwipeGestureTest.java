package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/** 左滑动作的纯几何：阈值、RTL 镜像、过冲阻尼与反方向拒绝。 */
public final class SwipeGestureTest {
  @Test
  public void horizontalRequiresSlopAndMostlyHorizontalMovement() {
    assertTrue(SwipeGesture.horizontal(-40f, 4f, 8));
    assertFalse("没过 touch slop", SwipeGesture.horizontal(-6f, 2f, 8));
    assertFalse("竖直为主时让给列表滚动", SwipeGesture.horizontal(-30f, 40f, 8));
    assertTrue(SwipeGesture.horizontal(30f, 8f, 8));
  }

  @Test
  public void revealDirectionFollowsLayoutDirection() {
    assertTrue(SwipeGesture.revealDirection(-10f, false));
    assertFalse(SwipeGesture.revealDirection(10f, false));
    assertTrue("RTL 向行尾（右侧）滑", SwipeGesture.revealDirection(10f, true));
    assertFalse(SwipeGesture.revealDirection(-10f, true));
  }

  @Test
  public void dragClampsToTrailingSideAndResistsOverdrag() {
    assertEquals(0f, SwipeGesture.dragOffset(30f, 168, false), 0.001f);
    assertEquals(-120f, SwipeGesture.dragOffset(-120f, 168, false), 0.001f);
    // 超出动作条宽度后只再跟手 30%，不会把整行拉出屏幕。
    assertEquals(-168f - 32f * 0.3f, SwipeGesture.dragOffset(-200f, 168, false), 0.001f);
    assertEquals(120f, SwipeGesture.dragOffset(120f, 168, true), 0.001f);
    assertEquals(0f, SwipeGesture.dragOffset(-120f, 168, true), 0.001f);
  }

  @Test
  public void opensAtFortyFivePercentOfStripWidth() {
    int width = 160;
    assertFalse(SwipeGesture.opens(-70f, width));
    assertTrue(SwipeGesture.opens(-72f, width));
    assertTrue(SwipeGesture.opens(100f, width));
    assertFalse(SwipeGesture.opens(0f, width));
    assertFalse("没有动作条宽度就没有阈值", SwipeGesture.opens(-100f, 0));
  }

  @Test
  public void settleTargetIsMirroredForRtl() {
    assertEquals(-160f, SwipeGesture.settleOffset(true, 160, false), 0.001f);
    assertEquals(0f, SwipeGesture.settleOffset(false, 160, false), 0.001f);
    assertEquals(160f, SwipeGesture.settleOffset(true, 160, true), 0.001f);
    assertEquals(0f, SwipeGesture.settleOffset(false, 160, true), 0.001f);
  }
}
