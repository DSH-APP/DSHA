package com.deepseekharness.app;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class LanRequestOriginTest {
  private static final List<String> HOST = List.of("192.168.1.5:3082");

  private boolean allowed(List<String> host, List<String> origin, List<String> site, boolean ws) {
    return LanRequestOrigin.allowed(host, origin, site, "192.168.1.5", 3082, ws);
  }

  @Test
  public void sameOriginAndNavigationAreAllowedButRebindingAndCrossOriginAreNot() {
    assertTrue(allowed(HOST, List.of(), List.of("none"), false));
    assertTrue(allowed(HOST, List.of("http://192.168.1.5:3082"), List.of("same-origin"), true));
    assertFalse(allowed(HOST, List.of("http://attacker.example"), List.of(), false));
    assertFalse(allowed(HOST, List.of("http://attacker.example"), List.of(), true));
    assertFalse(allowed(HOST, List.of(), List.of("cross-site"), false));
    assertFalse(allowed(HOST, List.of(), List.of("same-site"), false));
    assertFalse(allowed(HOST, List.of(), List.of(), true));
    assertFalse(allowed(List.of("attacker.example:3082"), List.of(), List.of(), false));
    assertFalse(allowed(List.of("192.168.1.6:3082"), List.of(), List.of(), false));
    assertFalse(
        allowed(
            HOST, List.of("http://192.168.1.5:3082", "http://attacker.example"), List.of(), false));
  }
}
