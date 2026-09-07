package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class UiThemePreferenceTest {
    @Test public void unknownPreferenceFollowsSystem() {
        for (String value : new String[]{null, "", "auto", "DARK", "system"}) {
            assertEquals("system", UiThemePreference.normalize(value));
            assertFalse(UiThemePreference.isDark(value, false));
            assertTrue(UiThemePreference.isDark(value, true));
        }
    }
    @Test public void explicitChoiceDoesNotChangeWithSystem() {
        for (boolean systemDark : new boolean[]{false, true}) {
            assertFalse(UiThemePreference.isDark("light", systemDark));
            assertTrue(UiThemePreference.isDark("dark", systemDark));
        }
    }
}
