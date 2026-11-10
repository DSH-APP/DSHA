package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NestedScrollArbiterTest {
  private static final float SLOP = 16f;

  @Test
  public void contentFitsGoesStraightToOuter() {
    NestedScrollArbiter a = new NestedScrollArbiter(SLOP);
    assertFalse(a.down(0, 0, false, false));
    assertFalse(a.move(0, -100, false, false));
  }

  @Test
  public void holdsOuterUntilSlopDecided() {
    NestedScrollArbiter a = new NestedScrollArbiter(SLOP);
    assertTrue(a.down(0, 100, false, true));
    assertTrue(a.move(0, 105, false, true));
    assertEquals(NestedScrollArbiter.Owner.UNDECIDED, a.owner());
  }

  @Test
  public void jitterTowardEdgeAtTopDoesNotReleaseUpwardScroll() {
    // 列表在顶部：先向下抖一帧，再上滑，内框必须拿到手势（旧实现在抖动帧就放开了外层）。
    NestedScrollArbiter a = new NestedScrollArbiter(SLOP);
    assertTrue(a.down(0, 100, false, true));
    assertTrue(a.move(0, 102, false, true));
    assertTrue(a.move(0, 70, false, true));
    assertEquals(NestedScrollArbiter.Owner.INNER, a.owner());
  }

  @Test
  public void innerLockSurvivesReachingEdgeMidGesture() {
    NestedScrollArbiter a = new NestedScrollArbiter(SLOP);
    a.down(0, 100, true, true);
    assertTrue(a.move(0, 60, true, true));
    // 滚到底后仍保持本次手势归内框，不中途跳到外层。
    assertTrue(a.move(0, 20, true, false));
    assertTrue(a.move(0, 30, true, false));
  }

  @Test
  public void startingAtEdgeAndPullingOutwardGoesToOuter() {
    NestedScrollArbiter a = new NestedScrollArbiter(SLOP);
    assertTrue(a.down(0, 100, false, true));
    assertFalse(a.move(0, 140, false, true));
    assertEquals(NestedScrollArbiter.Owner.OUTER, a.owner());
    // 已交外层后即使反向也不收回。
    assertFalse(a.move(0, 50, false, true));
  }

  @Test
  public void horizontalGestureGoesToOuter() {
    NestedScrollArbiter a = new NestedScrollArbiter(SLOP);
    a.down(0, 0, true, true);
    assertFalse(a.move(40, 10, true, true));
  }

  @Test
  public void endResetsForNextGesture() {
    NestedScrollArbiter a = new NestedScrollArbiter(SLOP);
    a.down(0, 0, true, true);
    a.move(0, -50, true, true);
    a.end();
    assertEquals(NestedScrollArbiter.Owner.OUTER, a.owner());
    assertTrue(a.down(0, 0, true, true));
  }
}
