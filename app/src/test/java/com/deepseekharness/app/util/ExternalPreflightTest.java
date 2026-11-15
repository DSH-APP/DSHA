package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExternalPreflightTest {
  @Test
  public void unknownSafetyFactsBlockFlash() {
    ExternalPreflight.Facts facts =
        new ExternalPreflight.Facts("target", "PJD110", true, false, false, false, -1, -1, -1, -1);
    assertFalse(ExternalPreflight.check("target", "init_boot", 1024, facts).allowed);
  }

  @Test
  public void unlockedUsbTargetWithCapacityCanPass() {
    ExternalPreflight.Facts facts =
        new ExternalPreflight.Facts("target", "PJD110", true, false, true, true, 80, 1, 1, 4096);
    assertTrue(ExternalPreflight.check("target", "init_boot", 1024, facts).allowed);
  }
}
