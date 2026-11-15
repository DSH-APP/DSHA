package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/** 子资源诊断只保留路径，并按页面限流；不能把 query、fragment 或主机写进事件。 */
public final class WebSubresourceDiagnosticTest {
  @Test
  public void pathDropsQueryFragmentAndHost() {
    assertEquals(
        "/assets/app.js",
        WebSubresourceDiagnostic.path("https://127.0.0.1:3080/assets/app.js?v=1#top"));
    assertEquals("/api/state", WebSubresourceDiagnostic.path("/api/state?token=secret"));
    assertEquals("/", WebSubresourceDiagnostic.path("https://example.com"));
    assertEquals("/", WebSubresourceDiagnostic.path(""));
    assertEquals("/", WebSubresourceDiagnostic.path(null));
  }

  @Test
  public void pathNeverLeaksUnsafeCharactersOrGrowsUnbounded() {
    String value = WebSubresourceDiagnostic.path("/a b" + "0123456789".repeat(60) + "#frag");
    assertFalse(value.contains("#"));
    assertFalse(value.contains(" "));
    assertEquals(WebSubresourceDiagnostic.PATH_LIMIT, value.length());
    assertTrue(value.startsWith("/a_b"));
    String query = WebSubresourceDiagnostic.path("/a?x=1&token=secret#frag");
    assertFalse(query.contains("?"));
    assertFalse(query.contains("#"));
    assertFalse(query.contains("token"));
    assertEquals("/a", query);
  }

  @Test
  public void describeKeepsFixedFieldOrderAndEnumPhase() {
    assertEquals(
        "phase=HTTP code=404 path=/ui/settings.js",
        WebSubresourceDiagnostic.describe("HTTP", 404, "/ui/settings.js?x=1"));
    assertEquals(
        "phase=RESOURCE",
        WebSubresourceDiagnostic.describe("free-text", 0, "/").split(" code=")[0]);
  }

  @Test
  public void limiterDeduplicatesAndCapsPerWindowAndPage() {
    WebSubresourceDiagnostic.Limiter limiter = WebSubresourceDiagnostic.Limiter.defaults();
    assertTrue(limiter.accept("/a.js", 1_000L));
    assertFalse(limiter.accept("/a.js", 2_000L));
    // 去重窗口过后同一路径可以再记一次。
    assertTrue(limiter.accept("/a.js", 61_001L));
    assertFalse(limiter.accept("/a.js", 61_002L));

    WebSubresourceDiagnostic.Limiter window = WebSubresourceDiagnostic.Limiter.defaults();
    int first = 0;
    for (int index = 0; index < 10; index++)
      if (window.accept("/b" + index + ".js", 1_000L)) first++;
    assertEquals(WebSubresourceDiagnostic.EVENTS_PER_WINDOW, first);
    assertFalse(window.accept("/late.js", 1_001L));
    // 时间窗滚过后额度恢复。
    assertTrue(window.accept("/late.js", 12_000L));
  }

  @Test
  public void limiterResetsPerPageAndNeverExceedsPageCap() {
    WebSubresourceDiagnostic.Limiter limiter = WebSubresourceDiagnostic.Limiter.defaults();
    int recorded = 0;
    for (int index = 0; index < 200; index++)
      if (limiter.accept("/resource-" + index + ".js", index * 70_000L)) recorded++;
    assertEquals(WebSubresourceDiagnostic.EVENTS_PER_PAGE, recorded);
    limiter.reset();
    assertEquals(0, limiter.pageTotal());
    assertTrue(limiter.accept("/resource-0.js", 0L));
  }
}
