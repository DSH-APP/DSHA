package com.deepseekharness.app.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.deepseekharness.app.util.OnboardingMotionPlan.Kind;
import org.junit.Test;

public final class OnboardingMotionPlanTest {
  @Test
  public void systemAnimationOffNeverAnimates() {
    assertEquals(Kind.NONE, OnboardingMotionPlan.choose(false, false, true, 0, 0));
    assertEquals(Kind.NONE, OnboardingMotionPlan.choose(false, false, false, 0, 2));
    assertEquals(Kind.NONE, OnboardingMotionPlan.choose(false, false, false, 3, 1));
  }

  @Test
  public void freshEntryPlaysIntroButRecreationDoesNot() {
    assertEquals(Kind.INTRO, OnboardingMotionPlan.choose(true, false, true, 0, 0));
    // 进程被清后由偏好恢复到第 3 步：仍是新实例首次显示，播放入场。
    assertEquals(Kind.INTRO, OnboardingMotionPlan.choose(true, false, true, 2, 2));
    // 旋转 / 分屏重建：不再演一遍入场。
    assertEquals(Kind.NONE, OnboardingMotionPlan.choose(true, true, true, 1, 1));
  }

  @Test
  public void directionFollowsStepOrder() {
    assertEquals(Kind.FORWARD, OnboardingMotionPlan.choose(true, false, false, 0, 1));
    assertEquals(Kind.FORWARD, OnboardingMotionPlan.choose(true, true, false, 1, 3));
    assertEquals(Kind.BACKWARD, OnboardingMotionPlan.choose(true, false, false, 2, 1));
    // 同一步原位刷新（例如被拦回第 1 步、轮询重绘）不动。
    assertEquals(Kind.NONE, OnboardingMotionPlan.choose(true, false, false, 0, 0));
  }

  @Test
  public void slideSignMirrorsForRtl() {
    assertEquals(1, OnboardingMotionPlan.slideSign(Kind.FORWARD, false));
    assertEquals(-1, OnboardingMotionPlan.slideSign(Kind.BACKWARD, false));
    assertEquals(-1, OnboardingMotionPlan.slideSign(Kind.FORWARD, true));
    assertEquals(1, OnboardingMotionPlan.slideSign(Kind.BACKWARD, true));
    assertEquals(0, OnboardingMotionPlan.slideSign(Kind.INTRO, false));
    assertEquals(0, OnboardingMotionPlan.slideSign(Kind.NONE, true));
  }

  @Test
  public void staggerIsCappedSoButtonsAreNotDelayedLong() {
    assertEquals(0L, OnboardingMotionPlan.delay(Kind.FORWARD, 0));
    assertEquals(35L, OnboardingMotionPlan.delay(Kind.FORWARD, 1));
    long cap = (OnboardingMotionPlan.MAX_STAGGER - 1) * OnboardingMotionPlan.STAGGER_MS;
    assertEquals(cap, OnboardingMotionPlan.delay(Kind.FORWARD, 5));
    assertEquals(cap, OnboardingMotionPlan.delay(Kind.BACKWARD, 40));
    assertEquals(
        (OnboardingMotionPlan.MAX_STAGGER - 1) * OnboardingMotionPlan.INTRO_STAGGER_MS,
        OnboardingMotionPlan.delay(Kind.INTRO, 99));
    assertEquals(0L, OnboardingMotionPlan.delay(Kind.NONE, 3));
    // 最慢一项（入场）也在 0.7 秒内完成。
    long worst =
        OnboardingMotionPlan.delay(Kind.INTRO, 99) + OnboardingMotionPlan.duration(Kind.INTRO);
    assertEquals(true, worst <= 700L);
  }

  @Test
  public void durations() {
    assertEquals(0L, OnboardingMotionPlan.duration(Kind.NONE));
    assertEquals(OnboardingMotionPlan.ITEM_MS, OnboardingMotionPlan.duration(Kind.FORWARD));
    assertEquals(OnboardingMotionPlan.ITEM_MS, OnboardingMotionPlan.duration(Kind.BACKWARD));
    assertEquals(OnboardingMotionPlan.INTRO_ITEM_MS, OnboardingMotionPlan.duration(Kind.INTRO));
  }

  @Test
  public void onlyNewlyReachedSegmentsFill() {
    assertArrayEquals(new int[] {1, 1}, OnboardingMotionPlan.filledSegments(Kind.FORWARD, 0, 1));
    assertArrayEquals(new int[] {2, 3}, OnboardingMotionPlan.filledSegments(Kind.FORWARD, 1, 3));
    assertArrayEquals(new int[] {0, 2}, OnboardingMotionPlan.filledSegments(Kind.INTRO, 2, 2));
    int[] none = OnboardingMotionPlan.filledSegments(Kind.BACKWARD, 3, 1);
    assertEquals(true, none[0] > none[1]);
    none = OnboardingMotionPlan.filledSegments(Kind.NONE, 0, 2);
    assertEquals(true, none[0] > none[1]);
  }
}
