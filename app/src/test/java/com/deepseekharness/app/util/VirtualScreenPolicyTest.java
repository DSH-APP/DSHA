package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualScreenPolicyTest {
  @Test
  public void gatesOldAndroidAndNormalizesPhoneRatios() {
    assertFalse(VirtualScreenPolicy.supported(29));
    assertTrue(VirtualScreenPolicy.supported(30));
    assertArrayEquals(new int[] {720, 1280}, VirtualScreenPolicy.phoneSize(720, 1520));
    assertArrayEquals(new int[] {1280, 720}, VirtualScreenPolicy.phoneSize(1520, 720));
  }

  @Test
  public void mapsCoordinates() {
    assertArrayEquals(
        new float[] {500f, 1000f}, VirtualScreenPolicy.map(50, 100, 100, 100, 1000, 1000), 0.001f);
    assertArrayEquals(
        new float[] {0f, 1000f}, VirtualScreenPolicy.map(-1, 200, 100, 100, 1000, 1000), 0.001f);
  }

  @Test
  public void inputAcceptsTheRegisteredObservedFrameEvenAfterTheScreenMovedOn() {
    // 画面从 4 前进到 400：旧规则（presented == current）会拒绝，新规则只看登记与时间窗。
    assertEquals(
        VirtualScreenPolicy.InputFrame.ACCEPTED,
        VirtualScreenPolicy.inputFrame(4, 4, 400, 1_000, 4_000));
    assertEquals(
        VirtualScreenPolicy.InputFrame.ACCEPTED,
        VirtualScreenPolicy.inputFrame(7, 7, 7, 1_000, 1_000 + 3_700));
    // 窗口边界（含）仍接受，超过 1ms 即过期。
    assertEquals(
        VirtualScreenPolicy.InputFrame.ACCEPTED,
        VirtualScreenPolicy.inputFrame(
            4, 4, 9, 1_000, 1_000 + VirtualScreenPolicy.OBSERVATION_WINDOW_MS));
    assertEquals(
        VirtualScreenPolicy.InputFrame.EXPIRED,
        VirtualScreenPolicy.inputFrame(
            4, 4, 9, 1_000, 1_001 + VirtualScreenPolicy.OBSERVATION_WINDOW_MS));
  }

  @Test
  public void inputRejectsFramesTheCallerNeverObservedAndImpossibleTimes() {
    // 出示的帧和上次登记的观察不是同一帧：不能把别处的帧拿来注入。
    assertEquals(
        VirtualScreenPolicy.InputFrame.STALE,
        VirtualScreenPolicy.inputFrame(3, 4, 9, 1_000, 2_000));
    // 比当前帧还新：不可能。
    assertEquals(
        VirtualScreenPolicy.InputFrame.STALE,
        VirtualScreenPolicy.inputFrame(9, 9, 4, 1_000, 2_000));
    // 没用过的帧号 / 没有登记过观察。
    assertEquals(
        VirtualScreenPolicy.InputFrame.STALE,
        VirtualScreenPolicy.inputFrame(0, 0, 9, 1_000, 2_000));
    assertEquals(
        VirtualScreenPolicy.InputFrame.STALE,
        VirtualScreenPolicy.inputFrame(-1, -1, 9, 1_000, 2_000));
    assertEquals(
        VirtualScreenPolicy.InputFrame.STALE,
        VirtualScreenPolicy.inputFrame(4, 4, 9, 0, 2_000));
    // 时钟回拨：不能把未来当成新鲜。
    assertEquals(
        VirtualScreenPolicy.InputFrame.STALE,
        VirtualScreenPolicy.inputFrame(4, 4, 9, 5_000, 1_000));
  }
}
