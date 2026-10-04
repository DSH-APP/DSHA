package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.WebStopDiagnostic;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebStopResultTest {
  @Test
  public void resultKeepsAllFactsAndLegacyTextIsRenderedOnlyWhenRead() {
    var ports = new RuntimeHostPorts();
    var locale = new AtomicReference<>("zh");
    ports.install(
        new RuntimeHostPorts.Provider() {
          public RuntimeHostPorts.Settings snapshot() {
            return new RuntimeHostPorts.Settings("auto", false, true, false);
          }

          public void stage(String text) {}

          public void record(String kind, String detail) {}

          public void failure(Throwable error) {}

          public String describeWebStop(WebStopDiagnostic value) {
            return locale.get() + ":" + value.code();
          }
        });
    var primary = WebStopDiagnostic.of(WebStopDiagnostic.Code.PENDING_EXIT);
    var result =
        new WebProcessManager.StopResult(
            WebProcessManager.StopStatus.UNCONFIRMED,
            "WEB_GUESTS_UNCONFIRMED",
            List.of(primary, WebStopDiagnostic.of(WebStopDiagnostic.Code.TRIAL_UNCONFIRMED)),
            ports);
    assertEquals(2, result.diagnostics().size());
    assertEquals(primary, result.diagnostic());
    assertFalse(result.stopped());
    assertEquals("zh:PENDING_EXIT\nzh:TRIAL_UNCONFIRMED", result.detail());
    locale.set("en");
    assertEquals("en:PENDING_EXIT\nen:TRIAL_UNCONFIRMED", result.detail());
    assertEquals(primary, result.diagnostic());
    assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
  }
}
