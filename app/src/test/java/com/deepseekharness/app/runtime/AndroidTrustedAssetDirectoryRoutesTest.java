package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import org.junit.Test;

public class AndroidTrustedAssetDirectoryRoutesTest {
  @Test
  public void repeatedRouteRetainsParsedStrings() {
    var routes = new AndroidTrustedAssetFileSystem.DirectoryRoutes();
    var route = routes.get("usr/local/lib/node_modules/pkg");
    assertSame(route, routes.get("usr/local/lib/node_modules/pkg"));
    assertEquals(5, route.size());
    assertEquals("usr", route.part(0));
    assertEquals("", route.before(0));
    assertEquals("usr/local/lib/node_modules", route.before(4));
    assertEquals("usr/local/lib/node_modules/pkg", route.prefix(4));
  }

  @Test
  public void routeCacheIsBoundedAndAccessOrdered() {
    var routes = new AndroidTrustedAssetFileSystem.DirectoryRoutes();
    var retained = routes.get("first");
    var evicted = routes.get("second");
    for (int index = 0; index < 62; index++) routes.get("route/" + index);
    assertSame(retained, routes.get("first"));
    routes.get("overflow");
    assertNotSame(evicted, routes.get("second"));
    assertSame(retained, routes.get("first"));
  }

  @Test
  public void sessionsDoNotShareRouteObjects() {
    var first = new AndroidTrustedAssetFileSystem.DirectoryRoutes();
    var second = new AndroidTrustedAssetFileSystem.DirectoryRoutes();
    assertNotSame(first.get("usr/local"), second.get("usr/local"));
  }

  @Test
  public void parsingPreservesExistingSplitAndPrefixSemantics() {
    var routes = new AndroidTrustedAssetFileSystem.DirectoryRoutes();
    for (String path : new String[] {"", "/", "a", "a/", "a//b", "/a/b", ".", "..", "a/../b"}) {
      var route = routes.get(path);
      String[] originalParts = path.split("/");
      assertEquals(path, originalParts.length, route.size());
      String prefix = "";
      for (int index = 0; index < originalParts.length; index++) {
        assertEquals(path, originalParts[index], route.part(index));
        assertEquals(path, prefix, route.before(index));
        prefix = prefix.isEmpty() ? originalParts[index] : prefix + "/" + originalParts[index];
        assertEquals(path, prefix, route.prefix(index));
      }
    }
  }

  @Test
  public void invalidInputDoesNotPopulateOrPoisonTheCache() {
    var routes = new AndroidTrustedAssetFileSystem.DirectoryRoutes();
    var route = routes.get("usr/local");
    assertThrows(NullPointerException.class, () -> routes.get(null));
    assertSame(route, routes.get("usr/local"));
  }
}
