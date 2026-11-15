package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class WebPortSettingsTest {
  @Test
  public void migratesDefaultButPreservesCustomAndExplicitManualPort() {
    assertEquals("auto", WebPortSettings.mode(null, null));
    assertEquals("auto", WebPortSettings.mode(null, "3080"));
    assertEquals("fixed", WebPortSettings.mode(null, "45678"));
    assertEquals("fixed", WebPortSettings.mode("fixed", "3080"));
    assertEquals("auto", WebPortSettings.mode("auto", "45678"));
    assertEquals("auto", WebPortSettings.mode(null, "3090"));
    assertEquals("auto", WebPortSettings.mode(null, "3091"));
    assertEquals("auto", WebPortSettings.mode(null, "3099"));
    assertEquals("auto", WebPortSettings.mode(null, "3082"));
    assertEquals("auto", WebPortSettings.mode(null, "invalid"));
    assertEquals("auto", WebPortSettings.mode("invalid", "45678"));
  }

  @Test
  public void onlyFixedModeRequiresAValidUnreservedPort() {
    assertEquals(0, WebPortSettings.requested("auto", 3080));
    assertEquals(45678, WebPortSettings.requested("fixed", 45678));
    for (int port : new int[] {0, -1, 3081, 3082, 3090, 3091, 3099, 65536}) {
      try {
        WebPortSettings.requested("fixed", port);
        fail();
      } catch (IllegalArgumentException expected) {
        assertEquals("WEB_FIXED_PORT", expected.getMessage());
      }
    }
    try {
      WebPortSettings.requireMode("unknown");
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("WEB_PORT_MODE", expected.getMessage());
    }
  }

  @Test
  public void explicitFixedImportsNeverNormalizeToAnotherPort() {
    assertEquals(3080, WebPortSettings.fixedImport("3080"));
    assertEquals(45678, WebPortSettings.fixedImport(" 45678 "));
    for (Object value :
        new Object[] {
          null, 3080, false, "", "oops", "0", "65536", "3081", "3082", "3090", "3091", "3099"
        }) {
      try {
        WebPortSettings.fixedImport(value);
        fail(String.valueOf(value));
      } catch (IllegalArgumentException expected) {
        assertEquals("WEB_FIXED_PORT", expected.getMessage());
      }
    }
  }
}
