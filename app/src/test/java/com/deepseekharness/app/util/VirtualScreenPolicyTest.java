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
  public void mapsCoordinatesAndRejectsStaleFrames() {
    assertArrayEquals(
        new float[] {500f, 1000f}, VirtualScreenPolicy.map(50, 100, 100, 100, 1000, 1000), 0.001f);
    assertArrayEquals(
        new float[] {0f, 1000f}, VirtualScreenPolicy.map(-1, 200, 100, 100, 1000, 1000), 0.001f);
    assertTrue(VirtualScreenPolicy.fresh(4, 4));
    assertFalse(VirtualScreenPolicy.fresh(3, 4));
  }

  @Test
  public void requestsTrustedDisplayOnlyWhenTheCallerActuallyHoldsIt() {
    // Android 12 无 root：ADB/Shizuku 通道是 uid=2000，且系统的 shell 包未申请该签名级权限。
    // 请求 TRUSTED 会被 DisplayManagerService 直接抛 SecurityException（issue #123）。
    assertFalse(VirtualScreenPolicy.requestTrustedDisplay(2000, false));
    // Root 通道：uid 0 由 checkCallingPermission 放行。
    assertTrue(VirtualScreenPolicy.requestTrustedDisplay(0, false));
    // Android 13+ 平台签名的 shell、以及其它确实持有该权限的调用方。
    assertTrue(VirtualScreenPolicy.requestTrustedDisplay(2000, true));
    assertTrue(VirtualScreenPolicy.requestTrustedDisplay(10123, true));
  }

  @Test
  public void namesWhyAnInputFrameWasRejected() {
    // 放行：刚看过这一帧，而且它仍是最新帧。
    assertNull(VirtualScreenPolicy.frameRejection(5, 5));
    assertNull(VirtualScreenPolicy.observationRejection(5, 5, 1200));
    // issue #117 的 1a：用 status 的 frameSeq —— 帧是最新的，但从没 see 过这一帧。
    assertEquals("FRAME_NOT_OBSERVED", VirtualScreenPolicy.observationRejection(5, -1, 0));
    // 观察已被上一次输入消费（核心把 observed 复位为 -1）。
    assertEquals("FRAME_NOT_OBSERVED", VirtualScreenPolicy.observationRejection(5, -1, 900));
    // issue #117 的 1b：see 过，但已被新帧顶掉（≥1fps 界面的必然）。
    assertEquals("FRAME_EXPIRED", VirtualScreenPolicy.frameRejection(5, 9));
    // 观察超过 30 秒窗口。
    assertEquals("OBSERVATION_TIMEOUT", VirtualScreenPolicy.observationRejection(5, 5, 30_001));
    // 缺少帧号参数。
    assertEquals("FRAME_NOT_OBSERVED", VirtualScreenPolicy.frameRejection(-1, 5));
  }

  @Test
  public void namesWhyAnInputFrameWasRejectedInTheOrderTheCoreUses() {
    // 组合判定就是对外行为：先看观察，再看新鲜度。
    assertNull(VirtualScreenPolicy.inputRejection(5, 5, 5, 1200));
    // 用 status 的帧号（从没 see 过）：即使它仍是最新帧也必须是 NOT_OBSERVED。
    assertEquals("FRAME_NOT_OBSERVED", VirtualScreenPolicy.inputRejection(5, 5, -1, 0));
    // 观察被上一次输入消费后、又过了 40 秒：先说"没看过这一帧"，不说"超时"。
    assertEquals("FRAME_NOT_OBSERVED", VirtualScreenPolicy.inputRejection(5, 5, -1, 40_000));
    // see 过、但已被新帧顶掉。
    assertEquals("FRAME_EXPIRED", VirtualScreenPolicy.inputRejection(5, 9, 5, 900));
    // 看过、仍是最新帧，但超过 30 秒窗口。
    assertEquals("OBSERVATION_TIMEOUT", VirtualScreenPolicy.inputRejection(5, 5, 5, 30_001));
  }
}
