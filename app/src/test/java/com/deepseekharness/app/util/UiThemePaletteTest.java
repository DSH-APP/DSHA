package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UiThemePaletteTest {
  @Test
  public void unknownOrRetiredPalettesFallBackToStandard() {
    for (String value : new String[] {null, "", "ocean", "SAKURA", "standard"})
      assertEquals(UiThemePalette.STANDARD, UiThemePalette.normalize(value));
    assertFalse(UiThemePalette.needsOverlay("ocean"));
    assertFalse(UiThemePalette.needsOverlay(UiThemePalette.STANDARD));
  }

  @Test
  public void everyRegisteredPaletteIsSelectableOnceAndOnlyStandardSkipsTheOverlay() {
    assertTrue(UiThemePalette.selectable());
    assertEquals(UiThemePalette.STANDARD, UiThemePalette.REGISTERED[0]);
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (String id : UiThemePalette.REGISTERED) {
      assertTrue(seen.add(id));
      assertEquals(id, UiThemePalette.normalize(id));
      assertEquals(!UiThemePalette.STANDARD.equals(id), UiThemePalette.needsOverlay(id));
    }
    assertEquals(5, seen.size());
  }

  @Test
  public void staleComparesNormalizedValues() {
    assertFalse(UiThemePalette.stale("standard", null));
    assertFalse(UiThemePalette.stale("ocean", "standard"));
    assertTrue(UiThemePalette.stale("standard", "sakura"));
    assertTrue(UiThemePalette.stale("cyber", "amber"));
  }
}
