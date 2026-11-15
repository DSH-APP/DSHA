package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualScreenPreviewPolicyTest {
  @Test
  public void defaultsToTheStripAndRoundTripsEveryMode() {
    assertEquals(VirtualScreenPreviewPolicy.Mode.STRIP, VirtualScreenPreviewPolicy.parse(null));
    assertEquals(VirtualScreenPreviewPolicy.Mode.STRIP, VirtualScreenPreviewPolicy.parse(""));
    assertEquals(
        VirtualScreenPreviewPolicy.Mode.STRIP, VirtualScreenPreviewPolicy.parse("unknown"));
    for (VirtualScreenPreviewPolicy.Mode mode : VirtualScreenPreviewPolicy.Mode.values())
      assertEquals(mode, VirtualScreenPreviewPolicy.parse(VirtualScreenPreviewPolicy.stored(mode)));
    // 持久化键是用户选择的身份，改名等于让老用户选择失效。
    assertEquals("vscreen_preview_mode", VirtualScreenPreviewPolicy.KEY_MODE);
    assertEquals("vscreen_preview_collapsed", VirtualScreenPreviewPolicy.KEY_COLLAPSED);
  }

  @Test
  public void visibilityNeedsRunningForegroundHostWithoutCollapseOrOverlay() {
    VirtualScreenPreviewPolicy.Mode strip = VirtualScreenPreviewPolicy.Mode.STRIP;
    assertTrue(VirtualScreenPreviewPolicy.visible(strip, false, true, true, false, false));
    // 关闭档与收起意图都隐藏；收起跨代次，不按 generation 记忆。
    assertTrue(
        VirtualScreenPreviewPolicy.visible(
            VirtualScreenPreviewPolicy.Mode.FULL, false, true, true, false, false));
    assertFalse(
        VirtualScreenPreviewPolicy.visible(
            VirtualScreenPreviewPolicy.Mode.OFF, false, true, true, false, false));
    assertFalse(VirtualScreenPreviewPolicy.visible(strip, true, true, true, false, false));
    // 虚拟屏未运行、宿主页不在前台、当前是虚拟识屏页、系统悬浮预览让位。
    assertFalse(VirtualScreenPreviewPolicy.visible(strip, false, false, true, false, false));
    assertFalse(VirtualScreenPreviewPolicy.visible(strip, false, true, false, false, false));
    assertFalse(VirtualScreenPreviewPolicy.visible(strip, false, true, true, true, false));
    assertFalse(VirtualScreenPreviewPolicy.visible(strip, false, true, true, false, true));
    assertFalse(VirtualScreenPreviewPolicy.visible(null, false, true, true, false, false));
  }

  @Test
  public void targetsPreferTheAppNameThenFallBackToThePackage() {
    assertEquals(
        "微信 · com.tencent.mm", VirtualScreenPreviewPolicy.targetLabel("微信", "com.tencent.mm", 32));
    assertEquals(
        "com.tencent.mm", VirtualScreenPreviewPolicy.targetLabel("", " com.tencent.mm ", 32));
    assertEquals("微信", VirtualScreenPreviewPolicy.targetLabel("微信", "", 32));
    assertEquals("微信", VirtualScreenPreviewPolicy.targetLabel("微信", "微信", 32));
    assertEquals("", VirtualScreenPreviewPolicy.targetLabel("  ", "", 32));
    // 拿不到目标时回退到运行中文案，不猜测应用。
    assertEquals("虚拟屏操作中", VirtualScreenPreviewPolicy.stripText("", "", "虚拟屏操作中", "虚拟屏 · ", 32));
    assertEquals(
        "虚拟屏 · 微信", VirtualScreenPreviewPolicy.stripText("微信", "", "虚拟屏操作中", "虚拟屏 · ", 32));
  }

  @Test
  public void truncatesByCodePointWithoutSplittingSurrogatePairs() {
    String emoji = "虚拟屏操作中😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀";
    String cut = VirtualScreenPreviewPolicy.truncate(emoji, 6);
    assertEquals(6, cut.codePointCount(0, cut.length()));
    assertTrue(cut.endsWith("…"));
    // 不允许留下半个代理对。
    for (int i = 0; i < cut.length(); i++)
      if (Character.isHighSurrogate(cut.charAt(i)))
        assertTrue(i + 1 < cut.length() && Character.isLowSurrogate(cut.charAt(i + 1)));
    assertEquals("…", VirtualScreenPreviewPolicy.truncate("虚拟屏操作中", 1));
    assertEquals("", VirtualScreenPreviewPolicy.truncate(null, 4));
    assertEquals("", VirtualScreenPreviewPolicy.truncate("虚拟屏操作中", 0));
  }

  @Test
  public void breathingFallsBackToAStaticBarWhenAnimationsAreOff() {
    assertEquals(
        VirtualScreenPreviewPolicy.BREATH_DIM_ALPHA,
        VirtualScreenPreviewPolicy.breathAlpha(true, true),
        0.001f);
    assertEquals(
        VirtualScreenPreviewPolicy.BREATH_FULL_ALPHA,
        VirtualScreenPreviewPolicy.breathAlpha(true, false),
        0.001f);
    assertEquals(
        VirtualScreenPreviewPolicy.BREATH_FULL_ALPHA,
        VirtualScreenPreviewPolicy.breathAlpha(false, true),
        0.001f);
  }

  @Test
  public void onlyTheStripAndFullModesSelectTheirOwnView() {
    assertTrue(VirtualScreenPreviewPolicy.strip(VirtualScreenPreviewPolicy.Mode.STRIP));
    assertFalse(VirtualScreenPreviewPolicy.strip(VirtualScreenPreviewPolicy.Mode.FULL));
    assertTrue(VirtualScreenPreviewPolicy.full(VirtualScreenPreviewPolicy.Mode.FULL));
    assertFalse(VirtualScreenPreviewPolicy.full(VirtualScreenPreviewPolicy.Mode.OFF));
  }
}
