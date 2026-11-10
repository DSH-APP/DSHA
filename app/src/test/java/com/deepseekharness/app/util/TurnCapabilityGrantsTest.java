package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class TurnCapabilityGrantsTest {
  @Test
  public void capabilityAndRevisionArePinnedToTheTurn() {
    var grants = new TurnCapabilityGrants();
    grants.remember("session\0turn", "external.open", 3);
    assertTrue(grants.allows("session\0turn", "external.open", 3));
    assertFalse(grants.allows("session\0other", "external.open", 3));
    assertFalse(grants.allows("session\0turn", "clipboard.read", 3));
    assertFalse(grants.allows("session\0turn", "external.open", 4));
  }

  @Test
  public void clearRetiresRememberedCapabilities() {
    var grants = new TurnCapabilityGrants();
    grants.remember("turn", "external.open", 1);
    grants.clear();
    assertFalse(grants.allows("turn", "external.open", 1));
  }
}
