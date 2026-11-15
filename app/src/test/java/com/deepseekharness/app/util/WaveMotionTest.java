package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class WaveMotionTest {
  private static final float EPS = 1e-3f;

  @Test
  public void bezierHitsEndpointsAndRejectsNonMonotonicX() {
    WaveMotion.Bezier standard = new WaveMotion.Bezier(0.22f, 1f, 0.36f, 1f);
    assertEquals(0f, standard.y(0f), EPS);
    assertEquals(1f, standard.y(1f), EPS);
    assertEquals(0f, standard.y(-1f), EPS);
    assertEquals(1f, standard.y(2f), EPS);
    float last = 0f;
    for (int i = 1; i <= 50; i++) {
      float y = standard.y(i / 50f);
      assertTrue("标准曲线必须单调", y >= last - EPS);
      last = y;
    }
    assertThrows(IllegalArgumentException.class, () -> new WaveMotion.Bezier(-0.1f, 0f, 0.5f, 1f));
    assertThrows(IllegalArgumentException.class, () -> new WaveMotion.Bezier(0.2f, 0f, 1.2f, 1f));
  }

  @Test
  public void linearBezierIsIdentity() {
    WaveMotion.Bezier linear = new WaveMotion.Bezier(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f);
    for (float x : new float[] {0.1f, 0.37f, 0.5f, 0.83f}) assertEquals(x, linear.y(x), EPS);
  }

  @Test
  public void fillOvershootsThenLandsExactlyOnTheRealTarget() {
    assertEquals(20f, WaveMotion.fill(20f, 60f, 0), EPS);
    assertEquals(60f, WaveMotion.fill(20f, 60f, WaveMotion.FILL_MS), EPS);
    assertEquals(60f, WaveMotion.fill(20f, 60f, WaveMotion.FILL_MS * 3), EPS);
    float peak = 0f;
    for (long t = 0; t <= WaveMotion.FILL_MS; t += 10)
      peak = Math.max(peak, WaveMotion.fill(20f, 60f, t));
    assertTrue("W3 追赶必须带回弹", peak > 60.5f);
    assertTrue("回弹幅度保持克制", peak < 60f + 40f * 0.25f);
  }

  @Test
  public void indeterminateSegmentStretchesButNeverCollapses() {
    float shortest = 1f, longest = 0f;
    for (int i = 0; i <= 100; i++) {
      float[] seg = WaveMotion.indeterminateSegment(i / 100f);
      float length = seg[1] - seg[0];
      assertTrue("段长下限", length >= WaveMotion.MIN_SEGMENT - EPS);
      assertTrue("头在尾前", seg[1] > seg[0]);
      shortest = Math.min(shortest, length);
      longest = Math.max(longest, length);
    }
    assertTrue("段长必须伸缩", longest - shortest > 0.3f);
    assertTrue("周期起点在轨道外", WaveMotion.indeterminateSegment(0f)[1] <= 0f);
    assertTrue("周期终点完全滑出", WaveMotion.indeterminateSegment(1f)[0] >= 1f);
  }

  @Test
  public void rippleSpeedsStayBoundedAndReflectMotion() {
    for (int i = 0; i <= 100; i++) {
      float speed = WaveMotion.indeterminateRippleSpeed(i / 100f);
      assertTrue(speed >= 0.7f - EPS && speed <= 2f);
    }
    assertTrue(
        WaveMotion.indeterminateRippleSpeed(0.05f) > WaveMotion.indeterminateRippleSpeed(0.95f));
    float fresh = WaveMotion.determinateRippleSpeed(0.3f, 0);
    float settled = WaveMotion.determinateRippleSpeed(0.3f, 1000);
    float stalled = WaveMotion.determinateRippleSpeed(0.3f, 5000);
    assertTrue("大跳变加速", fresh > 2.5f);
    assertEquals(1f, settled, EPS);
    assertEquals("长时间无数据放缓", 0.55f, stalled, EPS);
    assertTrue(WaveMotion.determinateRippleSpeed(0.01f, 0) < fresh);
  }

  @Test
  public void amplitudeFlattensTowardCompletion() {
    assertEquals(1f, WaveMotion.determinateAmplitude(0f), EPS);
    assertEquals(0.45f, WaveMotion.determinateAmplitude(1f), EPS);
    assertTrue(WaveMotion.determinateAmplitude(0.5f) > WaveMotion.determinateAmplitude(0.9f));
  }

  @Test
  public void terminalTimingMatchesExistingProgressContract() {
    assertEquals(1f, WaveMotion.terminalAlpha(0, true, true), EPS);
    long successHold = WaveMotion.SETTLE_MS + WaveMotion.SUCCESS_HOLD_MS;
    assertEquals(1f, WaveMotion.terminalAlpha(successHold, true, true), EPS);
    assertEquals(
        0.5f, WaveMotion.terminalAlpha(successHold + WaveMotion.FADE_MS / 2, true, true), 0.01f);
    assertEquals(0f, WaveMotion.terminalAlpha(successHold + WaveMotion.FADE_MS, true, true), EPS);
    long failureHold = WaveMotion.SETTLE_MS + WaveMotion.FAILURE_HOLD_MS;
    assertEquals(1f, WaveMotion.terminalAlpha(failureHold, false, true), EPS);
    assertEquals(successHold + WaveMotion.FADE_MS, WaveMotion.terminalDuration(true, true));
    // 关闭动画：不铺满、不淡出，停留后直接消失。
    assertEquals(1f, WaveMotion.settle(0, false), EPS);
    assertEquals(1f, WaveMotion.terminalAlpha(WaveMotion.SUCCESS_HOLD_MS, true, false), EPS);
    assertEquals(0f, WaveMotion.terminalAlpha(WaveMotion.SUCCESS_HOLD_MS + 1, true, false), EPS);
    assertEquals(WaveMotion.FAILURE_HOLD_MS, WaveMotion.terminalDuration(false, false));
  }

  @Test
  public void settleIsEaseOutAndTaperFadesEnds() {
    assertEquals(0f, WaveMotion.settle(0, true), EPS);
    assertEquals(1f, WaveMotion.settle(WaveMotion.SETTLE_MS, true), EPS);
    assertTrue(WaveMotion.settle(WaveMotion.SETTLE_MS / 2, true) > 0.5f);
    assertEquals(0f, WaveMotion.taper(10f, 10f, 100f, 14f), EPS);
    assertEquals(0.5f, WaveMotion.taper(17f, 10f, 100f, 14f), EPS);
    assertEquals(1f, WaveMotion.taper(55f, 10f, 100f, 14f), EPS);
    assertEquals(0f, WaveMotion.taper(100f, 10f, 100f, 14f), EPS);
  }
}
