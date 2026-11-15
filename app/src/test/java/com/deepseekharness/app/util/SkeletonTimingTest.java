package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 有限骨架时长：1–2 秒夹取、到点必须过期，不留无限动画。 */
public class SkeletonTimingTest {

  @Test
  public void durationIsClampedBetweenOneAndTwoSeconds() {
    assertEquals(SkeletonTiming.MIN_MS, SkeletonTiming.clampDuration(200));
    assertEquals(SkeletonTiming.MAX_MS, SkeletonTiming.clampDuration(60_000));
    assertEquals(1500L, SkeletonTiming.clampDuration(1500));
    assertEquals(SkeletonTiming.DEFAULT_MS, SkeletonTiming.clampDuration(0));
    assertEquals(SkeletonTiming.DEFAULT_MS, SkeletonTiming.clampDuration(-1));
  }

  @Test
  public void deadlineAndExpiryUseTheClampedDuration() {
    long deadline = SkeletonTiming.deadline(1_000, 60_000);
    assertEquals(1_000 + SkeletonTiming.MAX_MS, deadline);
    assertFalse(SkeletonTiming.expired(1_000 + SkeletonTiming.MAX_MS - 1, deadline));
    assertTrue(SkeletonTiming.expired(1_000 + SkeletonTiming.MAX_MS, deadline));
    assertEquals(0, SkeletonTiming.remaining(9_999, deadline));
    assertEquals(500, SkeletonTiming.remaining(2_500, deadline));
  }
}
