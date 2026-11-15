package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** 显示规格白名单、默认回退与观察预算核算（纯逻辑，不依赖 Android）。 */
public final class VirtualScreenDisplayPresetTest {
  private static final long MIB = 1024L * 1024;

  @Test
  public void defaultStaysAtTheExisting1008x1792At320() {
    VirtualScreenDisplayPreset.Preset preset = VirtualScreenDisplayPreset.defaultPreset();
    assertEquals("default", preset.id);
    assertEquals(1008, preset.width);
    assertEquals(1792, preset.height);
    assertEquals(320, preset.density);
    assertEquals(504, preset.logicalWidth());
    assertEquals(896, preset.logicalHeight());
    assertArrayEquals(new int[] {1008, 1792}, preset.size(false));
    assertArrayEquals(new int[] {1792, 1008}, preset.size(true));
    // 与既有 phoneSize() 归一化结果一致：默认创建的像素尺寸不变。
    assertArrayEquals(preset.size(false), VirtualScreenPolicy.phoneSize(1008, 1792));
  }

  @Test
  public void whitelistOffersOnlyTheDefaultAndTheCompat720Baseline() {
    List<VirtualScreenDisplayPreset.Preset> presets = VirtualScreenDisplayPreset.whitelist();
    assertEquals(2, presets.size());
    assertEquals("default", presets.get(0).id);
    assertEquals("compat-720", presets.get(1).id);

    VirtualScreenDisplayPreset.Preset compat = presets.get(1);
    assertEquals(720, compat.width);
    assertEquals(1600, compat.height);
    assertEquals(320, compat.density);
    assertEquals(360, compat.logicalWidth());
    assertEquals(800, compat.logicalHeight());
    assertArrayEquals(new int[] {720, 1600}, compat.size(false));
    assertArrayEquals(new int[] {1600, 720}, compat.size(true));
  }

  @Test
  public void unknownOrMissingIdsFallBackToTheDefaultInsteadOfBecomingACustomSize() {
    assertSame(VirtualScreenDisplayPreset.defaultPreset(), VirtualScreenDisplayPreset.resolve(""));
    assertSame(
        VirtualScreenDisplayPreset.defaultPreset(), VirtualScreenDisplayPreset.resolve("  "));
    assertSame(
        VirtualScreenDisplayPreset.defaultPreset(), VirtualScreenDisplayPreset.resolve("qhd-1440"));
    assertSame(
        VirtualScreenDisplayPreset.defaultPreset(),
        VirtualScreenDisplayPreset.resolve("1440x2560@560"));
    assertSame(
        VirtualScreenDisplayPreset.defaultPreset(),
        VirtualScreenDisplayPreset.resolve("compat-720-extra"));

    // 严格解析不替调用方猜意图：未知 id 返回 null，由设置/创建路径显式回退。
    assertNull(VirtualScreenDisplayPreset.parse("not-a-preset"));
    assertNull(VirtualScreenDisplayPreset.parse(null));
    assertEquals("compat-720", VirtualScreenDisplayPreset.parse("  compat-720  ").id);
  }

  @Test
  public void matchingNormalizesOrientationAndRefusesNonWhitelistGeometries() {
    assertEquals("default", VirtualScreenDisplayPreset.matching(1008, 1792, 320).id);
    assertEquals("default", VirtualScreenDisplayPreset.matching(1792, 1008, 320).id);
    assertEquals("compat-720", VirtualScreenDisplayPreset.matching(720, 1600, 320).id);
    assertNull(VirtualScreenDisplayPreset.matching(720, 1280, 320));
    assertNull(VirtualScreenDisplayPreset.matching(720, 1600, 240));
    assertNull(VirtualScreenDisplayPreset.matching(1440, 2560, 560));
  }

  @Test
  public void observationBudgetAcceptsTheWhitelistAndRejectsQhd1440() {
    // 默认：1008×1792×4 B×2 ≈ 13.8 MiB；兼容基线：720×1600×4 B×2 ≈ 8.8 MiB。
    assertEquals(14_450_688L, VirtualScreenDisplayPreset.defaultPreset().observationBytes());
    assertEquals(9_216_000L, VirtualScreenDisplayPreset.parse("compat-720").observationBytes());
    assertTrue(VirtualScreenDisplayPreset.fitsObservationBudget(1008, 1792));
    assertTrue(
        VirtualScreenDisplayPreset.fitsObservationBudget(
            VirtualScreenDisplayPreset.parse("compat-720")));

    // 1440×2560@560：两份原图约 28.1 MiB，超过 24 MiB 观察预算，所以不默认启用、也不在白名单里。
    assertEquals(
        28.125 * MIB, VirtualScreenDisplayPreset.observationBytes(1440, 2560), 0.001 * MIB);
    assertFalse(VirtualScreenDisplayPreset.fitsObservationBudget(1440, 2560));
    assertEquals(24 * MIB, VirtualScreenDisplayPreset.OBSERVED_BYTE_LIMIT);
    assertEquals(2, VirtualScreenDisplayPreset.OBSERVED_COUNT_LIMIT);
  }

  @Test
  public void everyWhitelistEntryFitsTheObservationBudget() {
    assertTrue(VirtualScreenDisplayPreset.whitelistFitsObservationBudget());
  }
}
