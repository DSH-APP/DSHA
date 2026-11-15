package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

/** 实体屏特权执行纯逻辑：帧配置、坐标、凭据、目标与 benchmark 统计。 */
public final class PrivilegedScreenPolicyTest {
  private static final String OBS = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  private static final String OTHER = "11111111-2222-3333-4444-555555555555";
  private static final String GEN = "gen-1";

  @Test
  public void frameConfigBoundsRejectInsteadOfClamping() {
    assertNull(PrivilegedScreenPolicy.configRejection(null, null, null));
    assertNull(PrivilegedScreenPolicy.configRejection(1280, 60, 6));
    assertEquals(
        "PRIV_INVALID_FRAME_WIDTH", PrivilegedScreenPolicy.configRejection(319, null, null));
    assertEquals(
        "PRIV_INVALID_FRAME_WIDTH", PrivilegedScreenPolicy.configRejection(1921, null, null));
    assertEquals(
        "PRIV_INVALID_JPEG_QUALITY", PrivilegedScreenPolicy.configRejection(null, 29, null));
    assertEquals(
        "PRIV_INVALID_JPEG_QUALITY", PrivilegedScreenPolicy.configRejection(null, 91, null));
    assertEquals("PRIV_INVALID_MAX_FPS", PrivilegedScreenPolicy.configRejection(null, null, 0));
    assertEquals("PRIV_INVALID_MAX_FPS", PrivilegedScreenPolicy.configRejection(null, null, 11));
  }

  @Test
  public void captureSizeKeepsAspectRatioAndLongEdgeBudget() {
    assertArrayEquals(new int[] {576, 1280}, PrivilegedScreenPolicy.captureSize(1080, 2400, 1280));
    assertArrayEquals(new int[] {727, 1600}, PrivilegedScreenPolicy.captureSize(1272, 2800, 1600));
    assertArrayEquals(new int[] {1920, 1200}, PrivilegedScreenPolicy.captureSize(1920, 1200, 1920));
    assertArrayEquals(new int[] {100, 50}, PrivilegedScreenPolicy.captureSize(100, 50, 320));
    int[] landscape = PrivilegedScreenPolicy.captureSize(2800, 1272, 1280);
    assertEquals(1280, landscape[0]);
    assertTrue(landscape[1] > 0 && landscape[1] < 1280);
  }

  @Test
  public void coordinatesMustBeFiniteAndInsideTheFrame() {
    assertNull(PrivilegedScreenPolicy.coordinateRejection(0, 0, 100, 200));
    assertNull(PrivilegedScreenPolicy.coordinateRejection(100, 200, 100, 200));
    assertEquals(
        "PRIV_COORDINATE_OUT_OF_BOUNDS",
        PrivilegedScreenPolicy.coordinateRejection(-1, 10, 100, 200));
    assertEquals(
        "PRIV_COORDINATE_OUT_OF_BOUNDS",
        PrivilegedScreenPolicy.coordinateRejection(10, 201, 100, 200));
    assertEquals(
        "PRIV_INVALID_COORDINATE",
        PrivilegedScreenPolicy.coordinateRejection(Float.NaN, 10, 100, 200));
    assertEquals(
        "PRIV_INVALID_COORDINATE",
        PrivilegedScreenPolicy.coordinateRejection(10, Float.POSITIVE_INFINITY, 100, 200));
    assertEquals(
        "PRIV_FRAME_SIZE_INVALID", PrivilegedScreenPolicy.coordinateRejection(1, 1, 0, 200));
  }

  @Test
  public void mappingConvertsFramePixelsToDisplayPixelsWithClamping() {
    float[] mapped = PrivilegedScreenPolicy.map(50, 100, 100, 200, 1000, 2000);
    assertArrayEquals(new float[] {500, 1000}, mapped, 0.001f);
    float[] clamped = PrivilegedScreenPolicy.map(100, 200, 100, 200, 1000, 2000);
    assertArrayEquals(new float[] {1000, 2000}, clamped, 0.001f);
    try {
      PrivilegedScreenPolicy.map(1, 1, 0, 1, 1, 1);
      fail("zero frame width must not map");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void swipeAndFrameWaitHaveIndependentBudgets() {
    assertNull(PrivilegedScreenPolicy.swipeDurationRejection(1));
    assertNull(PrivilegedScreenPolicy.swipeDurationRejection(3000));
    assertEquals("PRIV_INVALID_DURATION", PrivilegedScreenPolicy.swipeDurationRejection(0));
    assertEquals("PRIV_INVALID_DURATION", PrivilegedScreenPolicy.swipeDurationRejection(3001));
    assertNull(PrivilegedScreenPolicy.frameWaitRejection(0));
    assertNull(PrivilegedScreenPolicy.frameWaitRejection(500));
    assertEquals("PRIV_INVALID_FRAME_WAIT", PrivilegedScreenPolicy.frameWaitRejection(-1));
    assertEquals("PRIV_INVALID_FRAME_WAIT", PrivilegedScreenPolicy.frameWaitRejection(501));
  }

  @Test
  public void onlyTheFixedKeySetIsInjectable() {
    for (int code : new int[] {3, 4, 66, 67}) assertTrue(PrivilegedScreenPolicy.keyAllowed(code));
    for (int code : new int[] {0, 1, 26, 82, 187, 1000})
      assertFalse(PrivilegedScreenPolicy.keyAllowed(code));
  }

  @Test
  public void credentialRequiresObservedFrameGenerationAndFreshness() {
    assertNull(PrivilegedScreenPolicy.credentialRejection(7, OBS, 7, OBS, 10, GEN, GEN));
    assertEquals(
        "PRIV_STALE_GENERATION",
        PrivilegedScreenPolicy.credentialRejection(7, OBS, 7, OBS, 10, GEN, "gen-2"));
    assertEquals(
        "PRIV_FRAME_NOT_OBSERVED",
        PrivilegedScreenPolicy.credentialRejection(7, "", 7, OBS, 10, GEN, GEN));
    assertEquals(
        "PRIV_FRAME_NOT_OBSERVED",
        PrivilegedScreenPolicy.credentialRejection(7, OTHER, 7, OBS, 10, GEN, GEN));
    assertEquals(
        "PRIV_FRAME_NOT_OBSERVED",
        PrivilegedScreenPolicy.credentialRejection(6, OBS, 7, OBS, 10, GEN, GEN));
    assertEquals(
        "PRIV_OBSERVATION_TIMEOUT",
        PrivilegedScreenPolicy.credentialRejection(
            7, OBS, 7, OBS, PrivilegedScreenPolicy.OBSERVATION_WINDOW_MS + 1, GEN, GEN));
    // 游戏画面连续变化：只要观察未过期且目标代次未变，旧帧号仍然允许动作。
    assertNull(
        PrivilegedScreenPolicy.credentialRejection(
            7, OBS, 7, OBS, PrivilegedScreenPolicy.OBSERVATION_WINDOW_MS, GEN, GEN));
  }

  @Test
  public void observationIdsMustBeUuidShaped() {
    assertTrue(PrivilegedScreenPolicy.validObservationId(OBS));
    assertFalse(PrivilegedScreenPolicy.validObservationId(""));
    assertFalse(PrivilegedScreenPolicy.validObservationId("not-a-uuid"));
    assertFalse(PrivilegedScreenPolicy.validObservationId(OBS.toUpperCase()));
  }

  @Test
  public void targetMustMatchWhenTheCallerNamesOne() {
    assertNull(PrivilegedScreenPolicy.targetRejection("", "com.game", true));
    assertNull(PrivilegedScreenPolicy.targetRejection("com.game", "com.game", true));
    assertEquals(
        "PRIV_TARGET_CHANGED",
        PrivilegedScreenPolicy.targetRejection("com.game", "com.other", true));
    assertEquals(
        "PRIV_TARGET_UNVERIFIABLE", PrivilegedScreenPolicy.targetRejection("com.game", "", false));
    assertEquals(
        "PRIV_TARGET_UNVERIFIABLE",
        PrivilegedScreenPolicy.targetRejection("com.game", "com.game", false));
  }

  @Test
  public void blackFrameNeedsAnAlmostAllBlackSample() {
    assertTrue(PrivilegedScreenPolicy.secureOrBlack(98, 100));
    assertTrue(PrivilegedScreenPolicy.secureOrBlack(100, 100));
    assertFalse(PrivilegedScreenPolicy.secureOrBlack(97, 100));
    assertFalse(PrivilegedScreenPolicy.secureOrBlack(0, 0));
    assertFalse(PrivilegedScreenPolicy.secureOrBlack(10, 5));
  }

  @Test
  public void benchmarkBoundsKeepOneRequestInsideTheTransportBudget() {
    assertNull(PrivilegedScreenPolicy.benchmarkRejection(1, 0));
    assertNull(PrivilegedScreenPolicy.benchmarkRejection(20, 0));
    assertNull(PrivilegedScreenPolicy.benchmarkRejection(10, 250));
    assertEquals(
        "PRIV_INVALID_BENCHMARK_SAMPLES", PrivilegedScreenPolicy.benchmarkRejection(0, 100));
    assertEquals(
        "PRIV_INVALID_BENCHMARK_SAMPLES", PrivilegedScreenPolicy.benchmarkRejection(21, 0));
    assertEquals(
        "PRIV_BENCHMARK_BUDGET_EXCEEDED", PrivilegedScreenPolicy.benchmarkRejection(20, 500));
  }

  @Test
  public void summaryUsesNearestRankOverCompletedSamplesOnly() {
    PrivilegedScreenPolicy.Summary summary =
        PrivilegedScreenPolicy.Summary.of(new long[] {40, 10, 30, 20, -1});
    assertEquals(4, summary.count());
    assertEquals(20, summary.p50());
    assertEquals(40, summary.p95());
    assertEquals(40, summary.max());
    assertEquals(10, summary.min());
    assertEquals(-1, PrivilegedScreenPolicy.Summary.of(new long[0]).p50());
    assertEquals(-1, PrivilegedScreenPolicy.Summary.of(null).max());
    long[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    assertEquals(5, PrivilegedScreenPolicy.Summary.percentile(sorted, 50));
    assertEquals(10, PrivilegedScreenPolicy.Summary.percentile(sorted, 95));
    assertEquals(1, PrivilegedScreenPolicy.Summary.percentile(sorted, 1));
  }
}
