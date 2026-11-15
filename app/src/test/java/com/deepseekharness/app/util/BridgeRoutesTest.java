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
    assertEquals(BridgeRoutes.Route.INSTALL, BridgeRoutes.match("/app/install"));
    assertEquals(BridgeRoutes.Route.EXPORT, BridgeRoutes.match("/app/export"));
    assertEquals(BridgeRoutes.Route.SENSORS, BridgeRoutes.match("/app/sensors"));
    assertEquals(BridgeRoutes.Route.SENSOR, BridgeRoutes.match("/app/sensor"));
    assertEquals(BridgeRoutes.Route.TASK_CONTEXT, BridgeRoutes.match("/app/tasks/context"));
    assertEquals(BridgeRoutes.Route.TASK_EVENT, BridgeRoutes.match("/app/tasks/event"));
    assertEquals(BridgeRoutes.Route.TASK_CLAIM, BridgeRoutes.match("/app/tasks/claim"));
    assertEquals(BridgeRoutes.Route.TASK_ACK, BridgeRoutes.match("/app/tasks/ack"));
    assertEquals(BridgeRoutes.Route.CONSENT_POLICY, BridgeRoutes.match("/app/native-policy"));
    assertEquals(BridgeRoutes.Route.CONSENT_POLICY, BridgeRoutes.match("/device/consent"));
    assertEquals(BridgeRoutes.Route.ENTRY_TARGET, BridgeRoutes.match("/app/entry-target"));
    assertEquals(BridgeRoutes.Route.SAF_TREES, BridgeRoutes.match("/app/saf/trees"));
    assertEquals(BridgeRoutes.Route.SAF_LS, BridgeRoutes.match("/app/saf/ls"));
    assertEquals(BridgeRoutes.Route.SAF_STAT, BridgeRoutes.match("/app/saf/stat"));
    assertEquals(BridgeRoutes.Route.SAF_READ, BridgeRoutes.match("/app/saf/read"));
    assertEquals(BridgeRoutes.Route.SAF_WRITE, BridgeRoutes.match("/app/saf/write"));
    assertEquals(BridgeRoutes.Route.SAF_MKDIR, BridgeRoutes.match("/app/saf/mkdir"));
    assertEquals(BridgeRoutes.Route.SAF_RM, BridgeRoutes.match("/app/saf/rm"));
    assertEquals(BridgeRoutes.Route.SAF_PULL, BridgeRoutes.match("/app/saf/pull"));
    assertEquals(BridgeRoutes.Route.SAF_PUSH, BridgeRoutes.match("/app/saf/push"));
    assertEquals(BridgeRoutes.Route.UNKNOWN, BridgeRoutes.match("/app/saf/unknown"));
    assertEquals(BridgeRoutes.Route.NET, BridgeRoutes.match("/app/net"));
    assertEquals(BridgeRoutes.Route.NET_RESOLVE, BridgeRoutes.match("/app/net/resolve"));
    assertEquals(BridgeRoutes.Route.EXTDEV_STATUS, BridgeRoutes.match("/app/extdev/status"));
    assertEquals(BridgeRoutes.Route.EXTDEV_DISCOVER, BridgeRoutes.match("/app/extdev/discover"));
    assertEquals(BridgeRoutes.Route.EXTDEV_INSPECT, BridgeRoutes.match("/app/extdev/inspect"));
    assertEquals(BridgeRoutes.Route.EXTDEV_OPERATE, BridgeRoutes.match("/app/extdev/operate"));
    assertEquals(BridgeRoutes.Route.EXTDEV_WATCH, BridgeRoutes.match("/app/extdev/watch"));
    assertEquals(BridgeRoutes.Route.EXTDEV_PREFLIGHT, BridgeRoutes.match("/app/extdev/preflight"));
    assertEquals(BridgeRoutes.Route.EXTDEV_IMPORT, BridgeRoutes.match("/app/extdev/import"));
    for (String invalid :
        new String[] {
          "/app/readfile/secret",
          "/app/readfileXXX",
          "/app/share2",
          "/app/install/child",
          "/app/installXXX",
          "/app/export/child",
          "/app/sensorsXXX",
          "/app/sensor/child",
          "/app/overlay/reply",
          "/exec/child",
          "/app/tasks/event/child",
          "/app/tasks/context-extra",
          "/app/tasks%2fevent",
          "/app/task/event",
          "/app/native-policy/child",
          "/device/consent/child",
          "/app/entry-target/child",
          "/app/net/child",
          "/app/net/resolve/child",
          "/app/netXXX",
          "/app/entry-targetXXX",
          "/app/entry%2dtarget",
          "/app/extdev/watch/child",
          "/app/extdev/watchXXX"
        }) {
      assertEquals(invalid, BridgeRoutes.Route.UNKNOWN, BridgeRoutes.match(invalid));
    }
  }

  @Test
  public void namespacesDoNotCaptureTheirParentsOrEncodedSlash() {
    assertEquals(BridgeRoutes.Route.UI, BridgeRoutes.match("/app/ui/shot"));
    assertEquals(BridgeRoutes.Route.SENSE, BridgeRoutes.match("/app/sense/state"));
    assertEquals(BridgeRoutes.Route.VSCREEN, BridgeRoutes.match("/app/vscreen/status"));
    assertEquals(BridgeRoutes.Route.PRIV, BridgeRoutes.match("/app/priv/frame"));
    assertEquals(BridgeRoutes.Route.PRIV, BridgeRoutes.match("/app/priv/tap"));
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
  public void senseNamespaceRejectsParentsSuffixesAndEncodedSlashes() {
    for (String invalid :
        new String[] {
          "/app/sense", "/app/sense/other", "/app/sense%2fstate", "/app/sense-extra/state"
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
