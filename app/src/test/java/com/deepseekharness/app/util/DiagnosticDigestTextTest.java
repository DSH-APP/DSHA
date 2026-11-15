package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 诊断摘要：固定身份、有限事件行、脱敏与总长度上限。 */
public final class DiagnosticDigestTextTest {
  @Test
  public void containsIdentityAndLastEventsWithinLimit() {
    List<String> events = new ArrayList<>();
    for (int index = 0; index < 80; index++)
      events.add("01-01 00:00:00 [WEB_JS] event-" + index + " token=secret");
    String text =
        DiagnosticDigestText.build(
            "0.2.1",
            357,
            "c780bc22",
            "com.google.android.webview 1.2",
            12_345L,
            "pending=no",
            events);
    assertTrue(text.contains("version=0.2.1 (357)"));
    assertTrue(text.contains("runtimeId=c780bc22"));
    assertTrue(text.contains("freeBytes=12345"));
    assertTrue(text.contains("maintenance=pending=no"));
    assertTrue(text.contains("event-79"));
    assertFalse(text.contains("event-0 "));
    assertFalse(text.contains("token=secret"));
    assertTrue(text.length() <= DiagnosticDigestText.LIMIT);
    int lines = 0;
    for (String line : text.split("\n")) if (line.contains("event-")) lines++;
    assertEquals(DiagnosticDigestText.MAX_EVENTS, lines);
  }

  @Test
  public void emptyOrHostileFieldsBecomeSingleLineAndBounded() {
    String text =
        DiagnosticDigestText.build(
            "v\n1", 1, "id\nwith-line", "web", -5L, "stage\t\u0000x", List.of("a\nb", ""));
    assertFalse(text.contains("v\n1"));
    assertTrue(text.contains("freeBytes=0"));
    assertTrue(text.contains("maintenance=stage  x"));
    assertTrue(text.contains("a b"));
    assertTrue(text.length() <= DiagnosticDigestText.LIMIT);
  }

  @Test
  public void noEventsStillRendersStableSummary() {
    String text = DiagnosticDigestText.build("v", 1, "", "", 0, "", null);
    assertTrue(text.contains("recentEvents:\n(none)"));
    assertTrue(text.contains("runtimeId="));
    assertTrue(text.contains("maintenance=unknown"));
  }
}
