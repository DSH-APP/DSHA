package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 分组吸顶：未到不位移、越过贴顶、组底被顶走；边界不得出现负位移。 */
public class StickyHeaderMathTest {

  @Test
  public void headerStartsMovingOnlyAfterItReachesTheTop() {
    // 表头在 100，视口滚动到 100 之前保持原位。
    assertEquals(0, StickyHeaderMath.offset(0, 100, 24, 500));
    assertEquals(0, StickyHeaderMath.offset(99, 100, 24, 500));
    assertEquals(20, StickyHeaderMath.offset(120, 100, 24, 500));
  }

  @Test
  public void headerIsPushedOutAtTheEndOfItsGroup() {
    // 组底 300，表头 100 高 24：最多只能上移 176。
    assertEquals(176, StickyHeaderMath.offset(400, 100, 24, 300));
    assertEquals(0, StickyHeaderMath.offset(400, 100, 24, 124));
    assertEquals(0, StickyHeaderMath.offset(400, 100, 0, 300));
  }

  @Test
  public void activeGroupFollowsTheTopOfTheViewport() {
    int[] tops = {0, 200, 400};
    int[] heights = {24, 24, 24};
    assertEquals(-1, StickyHeaderMath.activeGroup(-1, tops, heights));
    assertEquals(0, StickyHeaderMath.activeGroup(0, tops, heights));
    assertEquals(0, StickyHeaderMath.activeGroup(150, tops, heights));
    assertEquals(1, StickyHeaderMath.activeGroup(200, tops, heights));
    assertEquals(2, StickyHeaderMath.activeGroup(650, tops, heights));
    assertEquals(-1, StickyHeaderMath.activeGroup(0, new int[0], new int[0]));
  }
}
