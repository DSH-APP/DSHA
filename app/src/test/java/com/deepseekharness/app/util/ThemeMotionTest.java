package com.deepseekharness.app.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ThemeMotionTest {
  private static final float EPS = 1e-4f;

  @Test
  public void revealOnlyGrowsSoTheOldThemeNeverComesBack() {
    assertEquals(0f, ThemeMotion.revealFraction(0f), EPS);
    assertEquals(0.875f, ThemeMotion.revealFraction(0.5f), EPS);
    assertEquals(1f, ThemeMotion.revealFraction(1f), EPS);
    float previous = -1f;
    for (int step = 0; step <= 100; step++) {
      float value = ThemeMotion.revealFraction(step / 100f);
      assertTrue("回缩会让旧主题闪回：" + step, value >= previous);
      assertTrue(value <= 1f);
      previous = value;
    }
  }

  @Test
  public void outOfRangeProgressIsClampedAndNanFinishes() {
    assertEquals(0f, ThemeMotion.revealFraction(-0.2f), EPS);
    assertEquals(1f, ThemeMotion.revealFraction(1.15f), EPS);
    assertEquals(1f, ThemeMotion.revealFraction(Float.NaN), EPS);
  }

  @Test
  public void stylesNormalizeAndOnlyNoneSkipsTheCapture() {
    for (String value : new String[] {null, "", "spring", "CIRCLE"})
      assertEquals(ThemeMotion.CIRCLE, ThemeMotion.normalizeStyle(value));
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (String style : ThemeMotion.STYLES) {
      assertEquals(style, ThemeMotion.normalizeStyle(style));
      assertTrue(seen.add(style));
      assertEquals(!ThemeMotion.NONE.equals(style), ThemeMotion.needsCapture(style));
      assertEquals(ThemeMotion.NONE.equals(style), ThemeMotion.durationMs(style) == 0L);
    }
    assertEquals(ThemeMotion.REVEAL_MS, ThemeMotion.durationMs("unknown"));
  }

  @Test
  public void easeInOutIsMonotonicSymmetricAndSlowAtBothEnds() {
    assertEquals(0f, ThemeMotion.easeInOut(0f), EPS);
    assertEquals(0.5f, ThemeMotion.easeInOut(0.5f), EPS);
    assertEquals(1f, ThemeMotion.easeInOut(1f), EPS);
    assertEquals(1f, ThemeMotion.easeInOut(Float.NaN), EPS);
    // 开头慢于线性（能看清从角里出发），结尾同理。
    assertTrue(ThemeMotion.easeInOut(0.1f) < 0.1f);
    assertTrue(ThemeMotion.easeInOut(0.9f) > 0.9f);
    float previous = -1f;
    for (int step = 0; step <= 100; step++) {
      float value = ThemeMotion.easeInOut(step / 100f);
      assertTrue(value >= previous);
      assertEquals(1f - value, ThemeMotion.easeInOut(1f - step / 100f), 1e-3f);
      previous = value;
    }
  }

  /** 屏幕上一点到渐变起点的投影，按渐变长度归一化后截到 [0,1]：0 = 新主题，1 = 旧主题。 */
  private static float oldThemeAlpha(float[] g, float x, float y) {
    float dx = g[2] - g[0], dy = g[3] - g[1];
    float t = ((x - g[0]) * dx + (y - g[1]) * dy) / (dx * dx + dy * dy);
    return Math.max(0f, Math.min(1f, t));
  }

  @Test
  public void diagonalRunsCornerToCornerAlongTheRealDiagonal() {
    float w = 400, h = 800;
    // 开始：整屏都是旧主题；结束：整屏都是新主题。
    float[] start = ThemeMotion.diagonalGradient(w, h, false, 0f);
    float[] end = ThemeMotion.diagonalGradient(w, h, false, 1f);
    for (float[] corner : new float[][] {{0, 0}, {w, 0}, {0, h}, {w, h}}) {
      assertEquals(1f, oldThemeAlpha(start, corner[0], corner[1]), EPS);
      assertEquals(0f, oldThemeAlpha(end, corner[0], corner[1]), EPS);
    }
    // 中途：左上角先变成新主题，右下角仍是旧主题。
    float[] mid = ThemeMotion.diagonalGradient(w, h, false, 0.5f);
    assertEquals(0f, oldThemeAlpha(mid, 0, 0), EPS);
    assertEquals(1f, oldThemeAlpha(mid, w, h), EPS);
    // 方向沿真实对角线（宽高比 1:2），不是 45°。
    assertEquals(h / w, (mid[3] - mid[1]) / (mid[2] - mid[0]), 1e-3f);
  }

  @Test
  public void diagonalMirrorsForRightToLeftLayouts() {
    float[] mid = ThemeMotion.diagonalGradient(400, 800, true, 0.5f);
    assertEquals(0f, oldThemeAlpha(mid, 400, 0), EPS);
    assertEquals(1f, oldThemeAlpha(mid, 0, 800), EPS);
    assertArrayEquals(
        new float[] {0f, 0f, 0f, 0f}, ThemeMotion.diagonalGradient(0, 0, false, 0.5f), EPS);
  }

  @Test
  public void maxRadiusReachesTheFarthestCorner() {
    assertEquals(500f, ThemeMotion.maxRadius(400, 300, 0, 0), EPS);
    assertEquals(500f, ThemeMotion.maxRadius(400, 300, 400, 300), EPS);
    assertEquals(250f, ThemeMotion.maxRadius(400, 300, 200, 150), EPS);
    // 点按位置越界（系统栏手势等）仍得到覆盖全屏的半径。
    assertTrue(ThemeMotion.maxRadius(400, 300, -10, 0) >= 500f);
  }

  @Test
  public void captureExpiresAndRejectsClockRollback() {
    assertTrue(ThemeMotion.captureFresh(1_000, 1_000));
    assertTrue(ThemeMotion.captureFresh(1_000, 1_000 + ThemeMotion.CAPTURE_TTL_MS));
    assertFalse(ThemeMotion.captureFresh(1_000, 1_001 + ThemeMotion.CAPTURE_TTL_MS));
    assertFalse(ThemeMotion.captureFresh(1_000, 999));
  }

  @Test
  public void splashHoldsOnlyAfterAnIntroAndOnlyUntilTheMinimum() {
    assertEquals(0L, ThemeMotion.splashHoldMs(false, 0, 0));
    assertEquals(ThemeMotion.SPLASH_MIN_VISIBLE_MS, ThemeMotion.splashHoldMs(true, 100, 100));
    assertEquals(200L, ThemeMotion.splashHoldMs(true, 0, ThemeMotion.SPLASH_MIN_VISIBLE_MS - 200));
    assertEquals(0L, ThemeMotion.splashHoldMs(true, 0, ThemeMotion.SPLASH_MIN_VISIBLE_MS + 5));
    assertEquals(ThemeMotion.SPLASH_MIN_VISIBLE_MS, ThemeMotion.splashHoldMs(true, 500, 100));
  }
}
