package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreferenceValueTest {
    @Test public void legacyNumericPortAndCountersAreReadable() {
        assertEquals("3080", PreferenceValue.text(3080, "fallback"));
        assertEquals(3080, PreferenceValue.integer("3080", 0));
        assertEquals(5, PreferenceValue.integer(5L, 0));
        assertEquals(7, PreferenceValue.integer(2147483648L, 7));
        assertEquals(9, PreferenceValue.integer("broken", 9));
        assertEquals(9, PreferenceValue.integer(1.5, 9));
    }
    @Test public void flagsHandleLegacyRepresentationsWithoutGuessing() {
        assertTrue(PreferenceValue.flag("true", false));
        assertTrue(PreferenceValue.flag(1, false));
        assertFalse(PreferenceValue.flag("0", true));
        assertFalse(PreferenceValue.flag("invalid", false));
        assertTrue(PreferenceValue.flag(null, true));
        assertEquals("fallback", PreferenceValue.text(java.util.Set.of("x"), "fallback"));
    }
}
