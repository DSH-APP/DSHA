package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

/** 实体屏特权端点只认完整精确路径，动作路由表与 Core/桥两位一体的语义一致。 */
public final class PrivilegedScreenRoutesTest {
  @Test
  public void bridgeRoutesMapOnlyTheDocumentedVerbs() {
    assertEquals("info", PrivilegedScreenRoutes.operation("/app/priv/info"));
    assertEquals("frame", PrivilegedScreenRoutes.operation("/app/priv/frame"));
    assertEquals("config", PrivilegedScreenRoutes.operation("/app/priv/config"));
    assertEquals("tap", PrivilegedScreenRoutes.operation("/app/priv/tap"));
    assertEquals("swipe", PrivilegedScreenRoutes.operation("/app/priv/swipe"));
    assertEquals("key", PrivilegedScreenRoutes.operation("/app/priv/key"));
    assertEquals("benchmark", PrivilegedScreenRoutes.operation("/app/priv/benchmark"));
    assertEquals("", PrivilegedScreenRoutes.operation("/app/priv/tap/extra"));
    assertEquals("", PrivilegedScreenRoutes.operation("/app/priv/"));
    assertEquals("", PrivilegedScreenRoutes.operation("/app/priv/TAP"));
    assertEquals("", PrivilegedScreenRoutes.operation("/app/ui/tap"));
    assertEquals("", PrivilegedScreenRoutes.operation(null));
  }

  @Test
  public void coreRoutesMapOnlyTheCoresOwnVerbs() {
    assertEquals("info", PrivilegedScreenRoutes.coreOperation("/priv/info"));
    assertEquals("frame", PrivilegedScreenRoutes.coreOperation("/priv/frame"));
    assertEquals("tap", PrivilegedScreenRoutes.coreOperation("/priv/tap"));
    assertEquals("", PrivilegedScreenRoutes.coreOperation("/priv/benchmark"));
    assertEquals("", PrivilegedScreenRoutes.coreOperation("/vscreen/tap"));
    assertEquals("", PrivilegedScreenRoutes.coreOperation("/priv/tap?x=1"));
  }

  @Test
  public void onlyTapSwipeKeyMutate() {
    for (String verb : new String[] {"info", "frame", "config", "close", "benchmark"})
      assertFalse(verb, PrivilegedScreenRoutes.mutates(verb));
    for (String verb : new String[] {"tap", "swipe", "key"})
      assertTrue(verb, PrivilegedScreenRoutes.mutates(verb));
    assertTrue(PrivilegedScreenRoutes.mutatesCore("/priv/tap"));
    assertFalse(PrivilegedScreenRoutes.mutatesCore("/priv/frame"));
    assertFalse(PrivilegedScreenRoutes.mutatesCore("/vscreen/tap"));
  }

  @Test
  public void contentReadsAreInfoAndFrameOnly() {
    assertTrue(PrivilegedScreenRoutes.readsContent("info"));
    assertTrue(PrivilegedScreenRoutes.readsContent("frame"));
    assertFalse(PrivilegedScreenRoutes.readsContent("tap"));
    assertFalse(PrivilegedScreenRoutes.readsContent(null));
    assertTrue(PrivilegedScreenRoutes.configures("config"));
    assertFalse(PrivilegedScreenRoutes.configures("frame"));
  }
}
