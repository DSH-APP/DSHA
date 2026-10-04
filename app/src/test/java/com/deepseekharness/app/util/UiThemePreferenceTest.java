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
}
