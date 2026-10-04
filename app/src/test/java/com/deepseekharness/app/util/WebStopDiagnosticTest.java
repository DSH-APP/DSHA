package com.deepseekharness.app.util;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebStopDiagnosticTest {
  @Test
  public void factsAreImmutableBoundedAndRedactedBeforeLeavingTheKernelBoundary() {
    var value =
        WebStopDiagnostic.of(
            WebStopDiagnostic.Code.CHECK_FAILED, 42, "Authorization: Bearer synthetic-secret");
    assertEquals(WebStopDiagnostic.Code.CHECK_FAILED, value.code());
    assertFalse(value.debug().contains("synthetic-secret"));
    assertThrows(UnsupportedOperationException.class, () -> value.arguments().add("later"));
    var large = WebStopDiagnostic.of(WebStopDiagnostic.Code.STOP_FAILED, "x".repeat(5000));
    assertEquals(1200, large.arguments().get(0).length());
  }

  @Test
  public void typedExceptionMessageAndFactsDoNotChangeWithUiLocale() {
    var exception = new WebStopException(WebStopDiagnostic.Code.CHECK_FAILED, 42, 13);
    String raw = exception.getMessage();
    try {
      UiText.setLanguage("en");
      assertEquals(raw, exception.getMessage());
      UiText.setLanguage("zh");
      assertEquals(raw, exception.getMessage());
      assertEquals(
          exception.diagnostic(),
          WebStopDiagnostic.failure(
              WebStopDiagnostic.Code.STOP_FAILED, new java.io.IOException("wrapper", exception)));
    } finally {
      UiText.setLanguage("zh");
    }
  }
}
