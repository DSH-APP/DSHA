package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class UiThemePreferenceTest {
  @Test
  public void unknownPreferenceFollowsSystem() {
    for (String value : new String[] {null, "", "auto", "DARK", "system"}) {
      assertEquals("system", UiThemePreference.normalize(value));
      assertFalse(UiThemePreference.isDark(value, false));
      assertTrue(UiThemePreference.isDark(value, true));
    }
  }

  @Test
  public void explicitChoiceDoesNotChangeWithSystem() {
    for (boolean systemDark : new boolean[] {false, true}) {
      assertFalse(UiThemePreference.isDark("light", systemDark));
      assertTrue(UiThemePreference.isDark("dark", systemDark));
    }
  }

  @Test
  public void cycleMakesSystemReachableAfterExplicitChoices() {
    assertEquals("light", UiThemePreference.next("system"));
    assertEquals("dark", UiThemePreference.next("light"));
    assertEquals("system", UiThemePreference.next("dark"));
    assertEquals("light", UiThemePreference.next(null));
  }

  @Test
  public void appearanceChangeOnlyWhenResolvedBrightnessFlips() {
    // 系统浅色：浅色 ↔ 跟随系统 不改变明暗，不能等待重建。
    assertFalse(UiThemePreference.changesAppearance("light", "system", false));
    assertTrue(UiThemePreference.changesAppearance("light", "system", true));
    assertTrue(UiThemePreference.changesAppearance("light", "dark", false));
    assertFalse(UiThemePreference.changesAppearance("dark", "system", true));
    assertFalse(UiThemePreference.changesAppearance("dark", "dark", false));
    // 未知旧值按跟随系统处理。
    assertFalse(UiThemePreference.changesAppearance("auto", "system", true));
  }

  @Test
  public void pageOrderCoversEveryNormalizedValueOnce() {
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (String value : UiThemePreference.ORDER) {
      assertEquals(value, UiThemePreference.normalize(value));
      assertTrue(seen.add(value));
    }
    assertEquals(3, seen.size());
  }
}
