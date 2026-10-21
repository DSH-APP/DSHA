package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 原生结果与轮询状态分别呈现；技术副本不带启动秘密或画面。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenStartOutcomeTest {
  @Test
  public void exceptionGraphRetainsCauseAndSuppressedWhileUnknownMessagesAreOmitted() {
    var original =
        new IllegalStateException("arbitrary request text", new java.io.IOException("CORE_EXITED"));
    original.addSuppressed(new IllegalArgumentException("another request value"));
    var captured =
        com.deepseekharness.app.diagnostics.Failure.capture(
            "VSCREEN_START_EXCEPTION",
            original,
            VirtualScreenManager.startExceptionMessagePolicy());
    assertEquals(IllegalStateException.class.getName(), captured.exceptionClass);
    assertEquals("EXCEPTION_MESSAGE_OMITTED", captured.summary);
    assertEquals(java.io.IOException.class.getName(), captured.cause.exceptionClass);
    assertEquals("CORE_EXITED", captured.cause.summary);
    assertEquals(1, captured.suppressed.size());
    assertEquals(
        IllegalArgumentException.class.getName(), captured.suppressed.get(0).exceptionClass);
    assertEquals("EXCEPTION_MESSAGE_OMITTED", captured.suppressed.get(0).summary);
  }

  @Test
  public void retainedTechnicalCopyExcludesHandshakeAndPixelMetadata() throws Exception {
    var safe =
        VirtualScreenManager.safeStartOutcome(
            new JSONObject()
                .put("ok", true)
                .put("token", "secret-bootstrap-value")
                .put("handle", "private-cleanup-identity")
                .put("previewB64", "private-pixels")
                .put("generation", "old-generation")
                .put("coreInstance", "private-core-identity")
                .put("displayId", 42));
    assertEquals(1, safe.length());
    assertTrue(safe.getBoolean("ok"));
    assertFalse(safe.toString().contains("private"));
  }

  @Test
  public void arbitraryCauseIsExplicitlyOmittedFromPublicResultCopy() throws Exception {
    var safe =
        VirtualScreenManager.safeStartOutcome(
            new JSONObject()
                .put("ok", false)
                .put("error", "VSCREEN_JOURNAL_UNAVAILABLE")
                .put(
                    "cause",
                    "IOException: /data/user/0/com.example/files/hidden " + "x".repeat(700)));
    assertFalse(safe.has("cause"));
    assertTrue(safe.getBoolean("causeOmitted"));
    assertFalse(safe.toString().contains("/data/user/0"));
  }

  @Test
  public void unexpectedFreeTextCannotBecomeAnErrorOrReasonCode() throws Exception {
    var safe =
        VirtualScreenManager.safeStartOutcome(
            new JSONObject()
                .put("ok", false)
                .put("error", "SYNTHETIC_BUSINESS_CANARY")
                .put("reason", "UPPERCASE_BUSINESS_BODY"));
    assertEquals("UNCLASSIFIED_FAILURE", safe.getString("error"));
    assertEquals("UNCLASSIFIED_FAILURE", safe.getString("reason"));
  }

  @Test
  public void missingOutcomeIsUnknownAndCannotReportSuccess() throws Exception {
    var safe = VirtualScreenManager.safeStartOutcome(null);
    assertFalse(safe.getBoolean("ok"));
    assertEquals("VSCREEN_START_RESULT_UNKNOWN", safe.getString("error"));
  }

  @Test
  public void causeAndReasonRemainSeparateFromTheStableFailureCode() throws Exception {
    var safe =
        VirtualScreenManager.safeStartOutcome(
            new JSONObject()
                .put("ok", false)
                .put("error", "STALE_FRAME")
                .put("reason", "FRAME_NOT_OBSERVED")
                .put("cause", "controlled platform cause"));
    assertEquals("STALE_FRAME", safe.getString("error"));
    assertEquals("FRAME_NOT_OBSERVED", safe.getString("reason"));
    assertFalse(safe.has("cause"));
    assertTrue(safe.getBoolean("causeOmitted"));
  }
}
