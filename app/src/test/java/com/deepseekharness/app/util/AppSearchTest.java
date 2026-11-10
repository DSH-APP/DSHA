package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class AppSearchTest {
  private record App(String label, String pkg) {}

  @Test
  public void emptyQueryMatchesEverything() {
    assertTrue(AppSearch.matches("", "微信", "com.tencent.mm"));
    assertTrue(AppSearch.matches("   ", "微信", "com.tencent.mm"));
    assertTrue(AppSearch.matches(null, null, null));
  }

  @Test
  public void matchesLabelOrPackageIgnoringCase() {
    assertTrue(AppSearch.matches("微", "微信", "com.tencent.mm"));
    assertTrue(AppSearch.matches("TENCENT", "微信", "com.tencent.mm"));
    assertTrue(AppSearch.matches(" chrome ", "Chrome", "com.android.chrome"));
    assertFalse(AppSearch.matches("alipay", "微信", "com.tencent.mm"));
    assertFalse(AppSearch.matches("x", null, null));
  }

  @Test
  public void configuredAppsComeFirstThenByLabel() {
    List<App> apps =
        List.of(
            new App("Zoom", "us.zoom"),
            new App("bilibili", "tv.danmaku.bili"),
            new App("Alipay", "com.eg.android.AlipayGphone"),
            new App("Chrome", "com.android.chrome"));
    Set<String> configured = Set.of("us.zoom");
    List<App> sorted =
        AppSearch.filterAndSort(apps, "", App::label, App::pkg, a -> configured.contains(a.pkg()));
    assertEquals(
        List.of("Zoom", "Alipay", "bilibili", "Chrome"), sorted.stream().map(App::label).toList());
  }

  @Test
  public void filterKeepsOnlyMatches() {
    List<App> apps = List.of(new App("Chrome", "com.android.chrome"), new App("Zoom", "us.zoom"));
    List<App> out = AppSearch.filterAndSort(apps, "android", App::label, App::pkg, a -> false);
    assertEquals(1, out.size());
    assertEquals("Chrome", out.get(0).label());
  }
}
