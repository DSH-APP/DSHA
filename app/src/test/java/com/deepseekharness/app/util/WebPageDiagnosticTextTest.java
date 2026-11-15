package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/** K0：页面保活诊断只记录可枚举事实，页面地址/token 不能进入日志行。 */
public final class WebPageDiagnosticTextTest {
  @Test
  public void engineLineCarriesPidAndGenerationOnly() {
    assertEquals("pid=4242 generation=7", WebPageDiagnosticText.engine(4242, 7));
  }

  @Test
  public void destroyLineKeepsTheReasonAndLifecycleFacts() {
    assertEquals(
        "reason=finish changingConfig=false finishing=true generation=7",
        WebPageDiagnosticText.pageDestroy("finish", false, true, 7));
  }

  @Test
  public void keepAliveLineUsesWhitelistedActionAndReason() {
    assertEquals(
        "action=DETACH reason=system generation=7 ready=true",
        WebPageDiagnosticText.keepAlive("DETACH", "system", 7, true));
    assertEquals(
        "action=UNKNOWN reason=unknown generation=-1 ready=false",
        WebPageDiagnosticText.keepAlive(
            "attach-all", "http://127.0.0.1:41821/?token=fixture", -1, false));
  }

  @Test
  public void urlsTokensAndControlCharactersDegradeToUnknown() {
    String[] hostile = {
      "http://127.0.0.1:41821/?token=fixture",
      "token=fixture",
      "system\nforged-line",
      "",
      "   ",
      "config with spaces",
      "a".repeat(25)
    };
    for (String value : hostile) {
      assertEquals(value, "unknown", WebPageDiagnosticText.reasonCode(value));
    }
    assertEquals("config", WebPageDiagnosticText.reasonCode("config"));
    // 大小写只是归一化，不是放行新原因：输出仍在小写白名单内。
    assertEquals("config", WebPageDiagnosticText.reasonCode("CONFIG"));
    assertEquals("task-removed", WebPageDiagnosticText.reasonCode("task-removed"));
    assertEquals("task-removed", WebPageDiagnosticText.reasonCode(" TASK-REMOVED "));
  }

  @Test
  public void noHostileValueSurvivesAnyPublicFormatter() {
    String hostile = "http://127.0.0.1:41821/?token=fixture";
    String page = WebPageDiagnosticText.pageDestroy(hostile, false, false, 7);
    String keep = WebPageDiagnosticText.keepAlive("ATTACH", hostile, 7, true);
    String engine = WebPageDiagnosticText.engine(42, 7);
    String resume =
        WebPageDiagnosticText.resume(
            hostile, hostile, hostile, hostile, hostile, 1, false, hostile, 1, 1, 1, true, true, 7);
    for (String line : new String[] {page, keep, engine, resume}) {
      assertFalse(line, line.contains("127.0.0.1"));
      assertFalse(line, line.contains("token"));
      assertFalse(line, line.contains("fixture"));
      assertFalse(line, line.contains("\n"));
    }
  }

  @Test
  public void resumeLineKeepsWhitelistedProbeFactsAndClampsNumbers() {
    assertEquals(
        "impl=k5fix2 trigger=native visibility=visible state=connected probe=alive elapsedMs=42 reset=false stateAfter=connected generation=3 generationAfter=3 hiddenMs=1200 inApp=true ready=true page=7",
        WebPageDiagnosticText.resume(
            "k5fix2",
            "native",
            "visible",
            "connected",
            "alive",
            42,
            false,
            "connected",
            3,
            3,
            1200,
            true,
            true,
            7));
  }

  @Test
  public void resumeLineSanitizesClientStringsAndClampsRanges() {
    String line =
        WebPageDiagnosticText.resume(
            "http://127.0.0.1:41821/?token=fixture",
            "token=fixture",
            "visible\nforged",
            "connected",
            "probe-with-very-long-name-x",
            999999,
            false,
            "127.0.0.1",
            -1,
            2,
            999999999,
            false,
            true,
            7);
    assertFalse(line, line.contains("127.0.0.1"));
    assertFalse(line, line.contains("token"));
    assertFalse(line, line.contains("fixture"));
    assertTrue(line, line.contains("impl=unknown"));
    assertTrue(line, line.contains("trigger=unknown"));
    assertTrue(line, line.contains("visibility=unknown"));
    assertTrue(line, line.contains("probe=unknown"));
    assertTrue(line, line.contains("stateAfter=unknown"));
    assertTrue(line, line.contains("elapsedMs=600000"));
    assertTrue(line, line.contains("hiddenMs=86400000"));
  }
}
