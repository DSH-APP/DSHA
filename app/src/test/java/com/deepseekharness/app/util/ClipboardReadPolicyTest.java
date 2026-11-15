package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ClipboardReadPolicyTest {
  @Test
  public void defaultAndUnknownModesAreOff() {
    assertEquals(ClipboardReadPolicy.Mode.OFF, ClipboardReadPolicy.Mode.decode(null));
    assertEquals(ClipboardReadPolicy.Mode.OFF, ClipboardReadPolicy.Mode.decode("future"));
    assertFalse(ClipboardReadPolicy.readsAllowed(ClipboardReadPolicy.Mode.OFF));
  }

  @Test
  public void askAndAllowHaveDifferentConfirmationSemantics() {
    assertTrue(ClipboardReadPolicy.readsAllowed(ClipboardReadPolicy.Mode.ASK));
    assertTrue(ClipboardReadPolicy.requiresConfirmation(ClipboardReadPolicy.Mode.ASK));
    assertTrue(ClipboardReadPolicy.readsAllowed(ClipboardReadPolicy.Mode.ALLOW));
    assertFalse(ClipboardReadPolicy.requiresConfirmation(ClipboardReadPolicy.Mode.ALLOW));
  }
}
