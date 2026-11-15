package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class StageSegmentMotionTest {
  private static final float EPS = 1e-3f;
  private static final int A = 0xFF4C8DF6;
  private static final int G = 0xFF34C759;

  private static boolean[] filled(int done, int total) {
    boolean[] out = new boolean[total];
    for (int i = 0; i < done; i++) out[i] = true;
    return out;
  }

  private static int[] colors(int color, int total) {
    int[] out = new int[total];
    java.util.Arrays.fill(out, color);
    return out;
  }

  @Test
  public void fillAndColorCurvesClampToEndpoints() {
    assertEquals(0f, StageSegmentMotion.fill(-50), EPS);
    assertEquals(0f, StageSegmentMotion.fill(0), EPS);
    assertEquals(1f, StageSegmentMotion.fill(StageSegmentMotion.FILL_MS), EPS);
    assertEquals(1f, StageSegmentMotion.fill(Long.MAX_VALUE / 4), EPS);
    float mid = StageSegmentMotion.fill(StageSegmentMotion.FILL_MS / 2);
    assertTrue(mid > 0.5f && mid < 1f);
    assertEquals(1f, StageSegmentMotion.colorMix(StageSegmentMotion.COLOR_MS), EPS);
  }

  @Test
  public void firstDisplayStaggersFilledSegmentsLeftToRight() {
    StageSegmentMotion.Tracker t = new StageSegmentMotion.Tracker();
    t.update("7:UPDATE", filled(4, 5), colors(A, 5), 1_000);
    assertEquals(5, t.size());
    assertTrue(t.animating(1_000));
    long at = 1_000 + StageSegmentMotion.FILL_MS / 2;
    float prev = 2f;
    for (int i = 0; i < 4; i++) {
      float f = t.fillFraction(i, at);
      assertTrue("越靠右越晚开始", f < prev);
      prev = f;
    }
    assertEquals(0f, t.fillFraction(4, at), EPS);
    long end = 1_000 + 3 * StageSegmentMotion.STAGGER_MS + StageSegmentMotion.FILL_MS;
    for (int i = 0; i < 4; i++) assertEquals(1f, t.fillFraction(i, end), EPS);
    assertFalse(t.animating(end));
  }

  @Test
  public void rebuildWithSameStateDoesNotReplay() {
    StageSegmentMotion.Tracker t = new StageSegmentMotion.Tracker();
    t.update("7:UPDATE", filled(3, 5), colors(A, 5), 0);
    t.update("7:UPDATE", filled(3, 5), colors(A, 5), 5_000);
    assertEquals(1f, t.fillFraction(0, 5_000), EPS);
    assertFalse(t.animating(5_000));
  }

  @Test
  public void nextStageFillsOnlyTheNewSegment() {
    StageSegmentMotion.Tracker t = new StageSegmentMotion.Tracker();
    t.update("7:UPDATE", filled(3, 5), colors(A, 5), 0);
    t.update("7:UPDATE", filled(4, 5), colors(A, 5), 5_000);
    assertEquals(1f, t.fillFraction(2, 5_000), EPS);
    assertEquals(0f, t.fillFraction(3, 5_000), EPS);
    assertTrue(t.animating(5_000));
    assertEquals(1f, t.fillFraction(3, 5_000 + StageSegmentMotion.FILL_MS), EPS);
  }

  @Test
  public void resultColorCrossFadesWithoutRefilling() {
    StageSegmentMotion.Tracker t = new StageSegmentMotion.Tracker();
    t.update("7:UPDATE", filled(5, 5), colors(A, 5), 0);
    t.update("7:UPDATE", filled(5, 5), colors(G, 5), 5_000);
    assertEquals(A, t.displayColor(0, 5_000));
    assertEquals(1f, t.fillFraction(0, 5_000), EPS);
    int mid = t.displayColor(0, 5_000 + StageSegmentMotion.COLOR_MS / 2);
    assertNotEquals(A, mid);
    assertNotEquals(G, mid);
    assertEquals(G, t.displayColor(0, 5_000 + StageSegmentMotion.COLOR_MS));
  }

  @Test
  public void newTaskOrRetryResetsAndUnfilledSegmentsClearImmediately() {
    StageSegmentMotion.Tracker t = new StageSegmentMotion.Tracker();
    t.update("7:UPDATE", filled(4, 5), colors(A, 5), 0);
    t.update("7:UPDATE", filled(1, 5), colors(A, 5), 5_000);
    assertEquals(0f, t.fillFraction(2, 5_000), EPS);
    t.update("8:REBUILD", filled(1, 6), colors(A, 6), 9_000);
    assertEquals(6, t.size());
    assertEquals(0f, t.fillFraction(0, 9_000), EPS);
    assertEquals(A, t.displayColor(0, 9_000));
  }

  @Test
  public void shimmerOnlyForRunningCurrentAndStaysOutsideDuringPause() {
    assertTrue(StageSegmentMotion.shimmer(DeployStages.Phase.RUNNING, DeployStages.Step.CURRENT));
    assertFalse(StageSegmentMotion.shimmer(DeployStages.Phase.RUNNING, DeployStages.Step.DONE));
    assertFalse(StageSegmentMotion.shimmer(DeployStages.Phase.FAILED, DeployStages.Step.CURRENT));
    assertFalse(StageSegmentMotion.filled(DeployStages.Step.TODO));
    assertTrue(StageSegmentMotion.filled(DeployStages.Step.FAILED));
    float w = StageSegmentMotion.SHIMMER_WIDTH;
    assertEquals(-w, StageSegmentMotion.shimmerCenter(0), EPS);
    long cycle = StageSegmentMotion.SHIMMER_CYCLE_MS;
    assertEquals(1f + w, StageSegmentMotion.shimmerCenter(cycle * 9 / 10), EPS);
    assertEquals(
        StageSegmentMotion.shimmerCenter(123), StageSegmentMotion.shimmerCenter(123 + cycle), EPS);
    assertEquals(
        StageSegmentMotion.shimmerCenter(cycle - 5), StageSegmentMotion.shimmerCenter(-5), EPS);
  }

  @Test
  public void mixInterpolatesEachChannel() {
    assertEquals(A, StageSegmentMotion.mix(A, G, 0f));
    assertEquals(G, StageSegmentMotion.mix(A, G, 1f));
    assertEquals(0x80808080, StageSegmentMotion.mix(0x00000000, 0xFFFFFFFF, 0.5f));
  }
}
