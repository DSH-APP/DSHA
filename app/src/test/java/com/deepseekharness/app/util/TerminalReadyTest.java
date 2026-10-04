package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TerminalReadyTest {
  @Test
  public void acceptsOnlyBoundedBirthIdentity() {
    TerminalReady ready = TerminalReady.parse("READY:3100:13100");
    assertNotNull(ready);
    assertEquals(3100, ready.session);
    assertEquals(13100, ready.started);
    assertNull(TerminalReady.parse("READY:3100"));
    assertNull(TerminalReady.parse("READY:3100:0"));
    assertNull(TerminalReady.parse("READY:1:13100"));
    assertNull(TerminalReady.parse("READY:3100:13100:extra"));
    assertNull(TerminalReady.parse("READY:3100:13100\nDONE:1:0"));
    assertNull(TerminalReady.parse("READY:9999999999:13100"));
    assertNull(TerminalReady.parse("READY:3100:99999999999999999999"));
  }
}
