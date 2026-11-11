package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/** 路由探针协议：只解析固定前缀与白名单字段，空白判定必须来自可读事实。 */
public final class WebRouteProbeTest {
  @Test
  public void parseKeepsOnlyKnownFieldsAndSanitizesPath() {
    WebRouteProbe.Result result =
        WebRouteProbe.parse(
            WebRouteProbe.PREFIX
                + "path=/config mount=%23root mountText=0 mountChildren=0 bodyText=0");
    assertNotNull(result);
    assertTrue(result.blank());
    assertEquals("/config", result.path);
    assertEquals("#root", result.mount);
  }

  @Test
  public void nonBlankWhenMountHasTextOrChildren() {
    assertFalse(
        WebRouteProbe.parse(
                WebRouteProbe.PREFIX
                    + "path=/a mount=#root mountText=12 mountChildren=3 bodyText=12")
            .blank());
    assertFalse(
        WebRouteProbe.parse(
                WebRouteProbe.PREFIX
                    + "path=/a mount=#root mountText=0 mountChildren=2 bodyText=30")
            .blank());
  }

  @Test
  public void noKnownMountFallsBackToBodyText() {
    assertTrue(
        WebRouteProbe.parse(WebRouteProbe.PREFIX + "path=/a mount= mountText=-1 bodyText=0")
            .blank());
    assertTrue(
        WebRouteProbe.parse(
                WebRouteProbe.PREFIX + "path=/a mount= mountText=-1 mountChildren=-1 bodyText=1")
            .blank());
    assertFalse(
        WebRouteProbe.parse(
                WebRouteProbe.PREFIX + "path=/a mount= mountText=-1 mountChildren=-1 bodyText=9")
            .blank());
  }

  @Test
  public void probeSampleIsBoundedAndMissingSampleIsUnknown() {
    assertEquals(
        2,
        WebRouteProbe.parse(WebRouteProbe.PREFIX + "path=/a mount=#root mountText=0 probe=2")
            .probe);
    assertEquals(
        1,
        WebRouteProbe.parse(WebRouteProbe.PREFIX + "path=/a mount=#root mountText=0 probe=01")
            .probe);
    assertEquals(
        -1, WebRouteProbe.parse(WebRouteProbe.PREFIX + "path=/a mount=#root mountText=0").probe);
    assertEquals(
        2,
        WebRouteProbe.parse(WebRouteProbe.PREFIX + "path=/a mount=#root mountText=0 probe=99")
            .probe);
  }

  @Test
  public void unknownMountAndNumbersAreIgnoredNotGuessed() {
    WebRouteProbe.Result result =
        WebRouteProbe.parse(
            WebRouteProbe.PREFIX
                + "path=/a mount=%23bogus mountText=abc mountChildren=-5 bodyText=-2 extra=secret");
    assertNotNull(result);
    assertEquals("", result.mount);
    assertEquals(-1, result.mountText);
    assertEquals(-1, result.mountChildren);
    assertEquals(-1, result.bodyText);
    assertFalse(result.blank());
    assertFalse(result.record().contains("secret"));
  }

  @Test
  public void malformedOrForeignLinesAreRejected() {
    assertNull(WebRouteProbe.parse(null));
    assertNull(WebRouteProbe.parse("[DSHA_PAGE] {}"));
    assertNull(WebRouteProbe.parse(WebRouteProbe.PREFIX));
    assertNull(WebRouteProbe.parse(WebRouteProbe.PREFIX + "no-fields"));
    assertNull(WebRouteProbe.parse(WebRouteProbe.PREFIX + "path=/a\npath=/b"));
  }

  @Test
  public void scriptInstallsErrorHookAndRouteProbeWithoutQueries() {
    String script = WebRouteProbe.script();
    assertTrue(script.contains("unhandledrejection"));
    assertTrue(script.contains("[DSHA_ROUTE]"));
    assertTrue(script.contains("__dshaRouteProbeUrl"));
    // 未捕获异常同样有界：每页最多 20 条、相同消息 5 秒内只报一次。
    assertTrue(script.contains("__dshaErrCount"));
    assertTrue(script.contains("<5000"));
    assertFalse(script.contains("document.cookie"));
    assertFalse(script.contains("localStorage"));
    // 一次性探针不得把完整 location.search/href 直接写进 console。
    assertFalse(script.contains("location.search"));
    assertFalse(script.contains("console.log(location"));
  }
}
