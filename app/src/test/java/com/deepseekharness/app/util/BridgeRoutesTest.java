package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BridgeRoutesTest {
  @Test
  public void exactRoutesStayDistinctFromSensitiveSuffixes() {
    assertEquals(BridgeRoutes.Route.READ_FILE, BridgeRoutes.match("/app/readfile"));
    assertEquals(BridgeRoutes.Route.SHARE, BridgeRoutes.match("/app/share"));
    assertEquals(BridgeRoutes.Route.EXPORT, BridgeRoutes.match("/app/export"));
    assertEquals(BridgeRoutes.Route.SENSORS, BridgeRoutes.match("/app/sensors"));
    assertEquals(BridgeRoutes.Route.SENSOR, BridgeRoutes.match("/app/sensor"));
    for (String invalid :
        new String[] {
          "/app/readfile/secret", "/app/readfileXXX", "/app/share2", "/app/export/child",
          "/app/sensorsXXX", "/app/sensor/child", "/app/overlay/reply", "/exec/child"
        }) {
      assertEquals(invalid, BridgeRoutes.Route.UNKNOWN, BridgeRoutes.match(invalid));
    }
  }

  @Test
  public void namespacesDoNotCaptureTheirParentsOrEncodedSlash() {
    assertEquals(BridgeRoutes.Route.UI, BridgeRoutes.match("/app/ui/shot"));
    assertEquals(BridgeRoutes.Route.VSCREEN, BridgeRoutes.match("/app/vscreen/status"));
    for (String invalid :
        new String[] {
          "/app/ui",
          "/app/vscreen",
          "/app/ui%2fshot",
          "/app/vscreen%2fstatus",
          "/app/ui-extra/tap",
          "/app/vscreen-extra/tap"
        }) {
      assertEquals(invalid, BridgeRoutes.Route.UNKNOWN, BridgeRoutes.match(invalid));
    }
  }

  @Test
  public void commandQueryOnlyBelongsToLegacyAndDeviceCommandEndpoints() {
    for (BridgeRoutes.Route route :
        new BridgeRoutes.Route[] {
          BridgeRoutes.Route.EXEC,
          BridgeRoutes.Route.CONFIRM,
          BridgeRoutes.Route.DEVICE_PLAN,
          BridgeRoutes.Route.DEVICE_EXECUTE,
          BridgeRoutes.Route.DEVICE_VSCREEN_START
        }) {
      assertTrue(route.name(), route.commandParameter());
    }
    for (BridgeRoutes.Route route : BridgeRoutes.Route.values()) {
      if (route == BridgeRoutes.Route.EXEC
          || route == BridgeRoutes.Route.CONFIRM
          || route == BridgeRoutes.Route.DEVICE_PLAN
          || route == BridgeRoutes.Route.DEVICE_EXECUTE
          || route == BridgeRoutes.Route.DEVICE_VSCREEN_START) continue;
      assertFalse(route.name(), route.commandParameter());
    }
  }
}
