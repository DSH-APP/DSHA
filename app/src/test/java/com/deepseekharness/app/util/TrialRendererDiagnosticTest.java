package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class TrialRendererDiagnosticTest {
  private static final String NONCE = "a".repeat(32);

  private static Map<String, Object> report() {
    Map<String, Object> facts = new HashMap<>();
    for (String key : TrialRendererDiagnostic.SCRIPT_FLAGS) facts.put(key, false);
    facts.put("postHttpStatus", 0);
    return facts;
  }

  @Test
  public void onlySameGenerationTypedFactsAreExportedWithoutSecretsOrUnknownFields() {
    var diagnostic = new TrialRendererDiagnostic(() -> 100L);
    Map<String, Object> incoming = report();
    incoming.put("webReady", true);
    incoming.put("url", "http://private/?token=secret");
    incoming.put("body", "private message");
    assertTrue(diagnostic.accept(NONCE, incoming));
    var current = diagnostic.snapshot(NONCE);
    assertEquals(true, current.get("webReady"));
    assertEquals(false, current.get("navigatorOnline"));
    assertEquals(true, current.get("nonceMatched"));
    assertFalse(current.toString().contains(NONCE));
    assertFalse(current.toString().contains("secret"));
    assertFalse(current.containsKey("body"));
    var old = diagnostic.snapshot("b".repeat(32));
    assertEquals(false, old.get("nonceMatched"));
    assertFalse(old.containsKey("webReady"));
  }

  @Test
  public void invalidPayloadCannotReplaceAcceptedStateOrSmuggleStrings() {
    var diagnostic = new TrialRendererDiagnostic(() -> 0L);
    assertTrue(diagnostic.accept(NONCE, report()));
    Map<String, Object> incoming = report();
    incoming.put("webReady", "secret");
    assertFalse(diagnostic.accept(NONCE, incoming));
    incoming = report();
    incoming.put("navigatorOnline", "false");
    assertFalse(diagnostic.accept(NONCE, incoming));
    incoming = report();
    incoming.put("postHttpStatus", Double.NaN);
    assertFalse(diagnostic.accept(NONCE, incoming));
    incoming.put("postHttpStatus", 200.5);
    assertFalse(diagnostic.accept(NONCE, incoming));
    assertFalse(diagnostic.accept("untrusted", report()));
    assertEquals(false, diagnostic.snapshot(NONCE).get("webReady"));
  }

  @Test
  public void nativeCodesAndAgingAreBoundedAndDoNotClaimReady() {
    long[] now = {0};
    var diagnostic = new TrialRendererDiagnostic(() -> now[0]);
    diagnostic.nativeFlag("viewShown", true);
    diagnostic.mainHttpStatus(404);
    diagnostic.mainLoadError(-2);
    assertEquals(false, diagnostic.snapshot(NONCE).get("scriptReportReceived"));
    assertTrue(diagnostic.accept(NONCE, report()));
    assertEquals(1, diagnostic.snapshot(NONCE).get("reportAgeBucket"));
    now[0] = 16_000;
    assertEquals(2, diagnostic.snapshot(NONCE).get("reportAgeBucket"));
    now[0] = 60_000;
    assertEquals(3, diagnostic.snapshot(NONCE).get("reportAgeBucket"));
    assertEquals(404, diagnostic.snapshot(NONCE).get("mainHttpStatus"));
    assertEquals(-2, diagnostic.snapshot(NONCE).get("mainLoadError"));
    diagnostic.mainHttpStatus(9999);
    assertEquals(0, diagnostic.snapshot(NONCE).get("mainHttpStatus"));
    try {
      diagnostic.nativeFlag("private-input", true);
      fail("Unknown diagnostic fields must not enter output");
    } catch (IllegalArgumentException expected) {
      assertEquals("TRIAL_DIAGNOSTIC_KEY", expected.getMessage());
    }
  }
}
