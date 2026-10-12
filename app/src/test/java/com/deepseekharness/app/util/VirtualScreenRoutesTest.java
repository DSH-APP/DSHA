package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualScreenRoutesTest {
  @Test
  public void everySupportedOperationRequiresItsExactFullPath() {
    for (String operation :
        new String[] {
          "create", "status", "launch", "tree", "node", "editor", "edit", "submit", "touch",
          "preview", "see", "tap", "swipe", "key", "type", "close"
        }) {
      assertEquals(operation, VirtualScreenRoutes.operation("/app/vscreen/" + operation));
      for (String invalid :
          new String[] {
            "/app/vscreen/unknown/" + operation,
            "/app/vscreen/" + operation + "XXX",
            "/app/vscreen/" + operation + "/",
            "/app/vscreen//" + operation,
            "/app/vscreen/../" + operation,
            "/app/vscreen%2f" + operation,
            "/x/app/vscreen/" + operation,
            operation
          }) assertEquals(invalid, "", VirtualScreenRoutes.operation(invalid));
    }
    assertEquals("", VirtualScreenRoutes.operation(null));
    assertEquals("", VirtualScreenRoutes.operation("/app/vscreen/unknown"));
  }

  @Test
  public void onlyActionEndpointsCountAsStateChanging() {
    for (String mutating :
        new String[] {"tap", "swipe", "key", "type", "touch", "node", "edit", "submit"})
      assertTrue(mutating, VirtualScreenRoutes.mutates(mutating));
    for (String readonly :
        new String[] {
          "create", "status", "launch", "tree", "editor", "preview", "see", "close", ""
        }) assertFalse(readonly, VirtualScreenRoutes.mutates(readonly));
    assertFalse(VirtualScreenRoutes.mutates(null));
  }

  @Test
  public void onlyCoreActionRoutesCountAsStateChanging() {
    for (String mutating : new String[] {"tap", "swipe", "key", "type", "touch"})
      assertTrue(mutating, VirtualScreenRoutes.mutatesCore("/vscreen/" + mutating));
    for (String readonly :
        new String[] {
          "create", "status", "launch", "tree", "editor", "preview", "see", "close", "check", "ping"
        }) assertFalse(readonly, VirtualScreenRoutes.mutatesCore("/vscreen/" + readonly));
    for (String invalid :
        new String[] {
          null,
          "tap",
          "/app/vscreen/tap",
          "/vscreen/tap?x=1",
          "/vscreen/tapXXX",
          "/vscreen/",
          "/x/vscreen/tap",
          "/vscreen/../tap"
        }) assertFalse(String.valueOf(invalid), VirtualScreenRoutes.mutatesCore(invalid));
  }
}
