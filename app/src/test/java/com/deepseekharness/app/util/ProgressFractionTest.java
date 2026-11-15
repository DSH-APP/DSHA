package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 真实进度分数：总量未知不得伪造百分比；运行中封顶 99。 */
public class ProgressFractionTest {

  @Test
  public void unknownTotalIsNotAProgress() {
    assertEquals(-1, ProgressFraction.percent(10, 0));
    assertEquals(-1, ProgressFraction.percent(10, -5));
    assertEquals(-1, ProgressFraction.percent(-1, 10));
  }

  @Test
  public void percentRoundsAndStopsBeforeOneHundred() {
    assertEquals(0, ProgressFraction.percent(0, 10));
    assertEquals(50, ProgressFraction.percent(5, 10));
    assertEquals(33, ProgressFraction.percent(1, 3));
    assertEquals(99, ProgressFraction.percent(10, 10));
    assertEquals(99, ProgressFraction.percent(12, 10));
  }

  @Test
  public void restorePrefersBytesThenEntries() {
    // 字节总量已知：按真实已复制字节。
    assertEquals(25, ProgressFraction.restorePercent(5, 25, 10, 100));
    // 字节未知时退回条目数，不把字节当成条目。
    assertEquals(50, ProgressFraction.restorePercent(5, 25, 10, 0));
    // 两者都未知：不确定态，不伪造。
    assertEquals(-1, ProgressFraction.restorePercent(5, 25, 0, 0));
  }
}
