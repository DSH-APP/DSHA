package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

/** 错误面板判定（docs/design/web-crash-20261102）：服务层优先、按钮互不重复、五层状态齐全。 */
public final class WebCrashReportTest {
  @Test
  public void endedRuntimeKeepsNormalStopsAndShowsCrashes() {
    // 用户停止与普通维护停止照常收尾，不显示错误面板。
    assertNull(WebCrashReport.ended(true, true, false, false));
    assertNull(WebCrashReport.ended(true, false, false, true));
    assertNull(WebCrashReport.ended(false, true, false, false));
    // 预算用尽时 Controller 自己停止，生命周期同样记为用户停止；仍须先说明原因。
    assertEquals(WebCrashReport.Kind.BLOCKED, WebCrashReport.ended(true, true, true, false));
    assertEquals(WebCrashReport.Kind.BLOCKED, WebCrashReport.ended(true, false, true, false));
    assertEquals(WebCrashReport.Kind.RESTARTING, WebCrashReport.ended(false, false, false, true));
    assertEquals(WebCrashReport.Kind.EXITED, WebCrashReport.ended(false, false, false, false));
  }

  @Test
  public void connectionRefusedMeansThePortIsClosed() {
    assertEquals(android.webkit.WebViewClient.ERROR_CONNECT, WebCrashReport.ERROR_CONNECT);
    assertEquals(WebCrashReport.Kind.HUNG, WebCrashReport.network(WebCrashReport.ERROR_CONNECT));
    assertEquals(WebCrashReport.Kind.NETWORK, WebCrashReport.network(-8));
    assertEquals(WebCrashReport.Kind.AUTH, WebCrashReport.http(401));
    assertEquals(WebCrashReport.Kind.AUTH, WebCrashReport.http(403));
    assertEquals(WebCrashReport.Kind.HTTP, WebCrashReport.http(502));
  }

  @Test
  public void everyKindHasDistinctActionsAndFiveOrderedRows() {
    WebCrashReport.Facts facts = new WebCrashReport.Facts();
    for (WebCrashReport.Kind kind : WebCrashReport.Kind.values()) {
      WebCrashReport.Spec spec = WebCrashReport.spec(kind);
      assertNotNull(kind.name(), spec);
      assertNotEquals(kind.name(), spec.primary, spec.secondary);
      assertNotEquals(kind.name(), WebCrashReport.Action.WAIT, spec.secondary);
      // 服务不在时不把浏览器打开当作出路。
      if (spec.serviceDown) {
        assertNotEquals(kind.name(), WebCrashReport.Action.BROWSER, spec.primary);
        assertNotEquals(kind.name(), WebCrashReport.Action.BROWSER, spec.secondary);
      }
      List<WebCrashReport.Row> rows = WebCrashReport.rows(kind, facts);
      assertEquals(kind.name(), 5, rows.size());
      for (int i = 0; i < rows.size(); i++)
        assertEquals(kind.name(), WebCrashReport.Layer.values()[i], rows.get(i).layer);
    }
  }

  @Test
  public void serviceLayerWinsAndKeepsRealFacts() {
    WebCrashReport.Facts facts = new WebCrashReport.Facts();
    facts.exitCode = 137;
    facts.failures = 3;
    List<WebCrashReport.Row> blocked = WebCrashReport.rows(WebCrashReport.Kind.BLOCKED, facts);
    assertEquals(WebCrashReport.Value.SERVICE_EXITED, blocked.get(0).value);
    assertEquals(137, blocked.get(0).number);
    assertEquals(WebCrashReport.Value.AUTO_PAUSED, blocked.get(1).value);
    assertEquals(3, blocked.get(1).number);
    assertEquals(WebCrashReport.Value.CONNECTION_NOT_APPLICABLE, blocked.get(4).value);

    // 退出码未知时不编造。
    List<WebCrashReport.Row> exited =
        WebCrashReport.rows(WebCrashReport.Kind.EXITED, new WebCrashReport.Facts());
    assertEquals(WebCrashReport.UNKNOWN_EXIT, exited.get(0).number);
    assertEquals(WebCrashReport.Value.AUTO_NONE, exited.get(1).value);
    assertEquals(WebCrashReport.Value.PAGE_CLOSED, exited.get(3).value);
  }

  @Test
  public void autoRecoveryFollowsTheControllerSwitch() {
    WebCrashReport.Facts facts = new WebCrashReport.Facts();
    assertEquals(
        WebCrashReport.Value.AUTO_OFF,
        WebCrashReport.rows(WebCrashReport.Kind.HUNG, facts).get(1).value);
    facts.autoRestart = true;
    assertEquals(
        WebCrashReport.Value.AUTO_WAITING_CHECKS,
        WebCrashReport.rows(WebCrashReport.Kind.HUNG, facts).get(1).value);
    assertEquals(
        WebCrashReport.Value.AUTO_WATCHING,
        WebCrashReport.rows(WebCrashReport.Kind.RENDER_CRASH, facts).get(1).value);
  }

  @Test
  public void keptPagesAreOnlyTheRecoveredOnes() {
    EnumSet<WebCrashReport.Kind> kept =
        EnumSet.of(WebCrashReport.Kind.EXITED_KEPT, WebCrashReport.Kind.VERIFY);
    for (WebCrashReport.Kind kind : WebCrashReport.Kind.values())
      assertEquals(
          kind.name(),
          kept.contains(kind),
          WebCrashReport.rows(kind, new WebCrashReport.Facts()).get(3).value
              == WebCrashReport.Value.PAGE_KEPT);
  }
}
