package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class ExternalFastbootPlanTest {
  @Test
  public void flashAlwaysBindsUsbSerialAndDoesNotAcceptExtraFlags() {
    var argv =
        ExternalFastbootPlan.flash(
            "/lib/ld-linux-aarch64.so.1",
            "/lib:/usr/lib",
            "/usr/bin/fastboot",
            "serial",
            "boot",
            "/data/image.img");
    assertEquals("-s", argv.get(4));
    assertEquals("serial", argv.get(5));
    assertEquals("flash", argv.get(6));
    try {
      ExternalFastbootPlan.flash("/loader", "/lib", "/fastboot", "192.168.1.2:5555", "boot", "x");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
