package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BootPaletteTest {
  @Test
  public void dshDefaultBackgroundsSelectTheMatchingDshLabelSet() {
    BootPalette light = BootPalette.fromWeb(0xFFFFFFFF, 0xFF000000);
    assertEquals(0xFFFFFFFF, light.background);
    assertEquals(BootPalette.LIGHT_PRIMARY, light.primary);
    assertEquals(BootPalette.LIGHT_TERTIARY, light.tertiary);
    assertEquals(BootPalette.LIGHT_BORDER, light.border);

    BootPalette dark = BootPalette.fromWeb(0xFF101114, 0xFFFFFFFF);
    assertEquals(BootPalette.DARK_PRIMARY, dark.primary);
    assertEquals(BootPalette.DARK_TERTIARY, dark.tertiary);
    assertEquals(BootPalette.DARK_BORDER, dark.border);
  }

  @Test
  public void customThemeColorsFollowLuminanceNotHue() {
    // 主题插件常见的有色深底 / 浅底。
    assertTrue(BootPalette.dark(0xFF0B2A4A));
    assertTrue(BootPalette.dark(0xFF3A0B2A));
    assertFalse(BootPalette.dark(0xFFF3F6FB));
    assertFalse(BootPalette.dark(0xFFFFE9A8));
  }

  @Test
  public void translucentBackgroundIsCompositedOverTheHostColor() {
    BootPalette palette = BootPalette.fromWeb(0x00FFFFFF, 0xFF0B0F16);
    assertEquals(0xFF0B0F16, palette.background);
    assertEquals(BootPalette.DARK_PRIMARY, palette.primary);
    // alpha 0x80：(255*128 + 0*127 + 127) / 255 = 128 → 0x80，结果完全不透明。
    assertEquals(0xFF808080, BootPalette.composite(0x80FFFFFF, 0xFF000000));
    assertEquals(0xFF123456, BootPalette.composite(0xFF123456, 0xFFFFFFFF));
  }
}
