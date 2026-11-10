package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExternalFastbootFactsTest {
  @Test
  public void parsesBootloaderFactsAndHexSizes() {
    ExternalFastbootFacts facts =
        ExternalFastbootFacts.parse(
            "(bootloader) product: PJD110\n(bootloader) unlocked: yes\n(bootloader) partition-size:init_boot: 0x800000\n(bootloader) is-userspace: yes");
    assertEquals("PJD110", facts.get("product"));
    assertTrue(facts.yes("unlocked"));
    assertTrue(facts.yes("is-userspace"));
    assertEquals(0x800000L, facts.size("init_boot"));
  }
}
