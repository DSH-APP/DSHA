package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** 停止预算必须覆盖 dsh 自身 5s 强制退出上限；只有“仍在退出”类事实才值得再等。 */
public final class WebStopBudgetTest {
  @Test
  public void exitBudgetCoversForcedShutdownWithMargin() {
    assertTrue(WebStopBudget.EXIT_CONFIRM_MILLIS >= 6_000);
    assertTrue(WebStopBudget.EXIT_CONFIRM_MILLIS <= 8_000);
    assertTrue(WebStopBudget.STARTUP_EXIT_MILLIS >= WebStopBudget.EXIT_CONFIRM_MILLIS);
    assertTrue(WebStopBudget.STARTUP_EXIT_MILLIS <= 10_000);
    assertTrue(WebStopBudget.POLL_MILLIS > 0);
    assertTrue(WebStopBudget.FORCE_RETRY_MILLIS > WebStopBudget.EXIT_CONFIRM_MILLIS);
  }

  @Test
  public void onlyInFlightFactsJustifyWaiting() {
    assertTrue(
        WebStopBudget.mayStillExit(
            List.of(
                WebStopDiagnostic.of(WebStopDiagnostic.Code.OWNED_WEB_REMAINS),
                WebStopDiagnostic.of(WebStopDiagnostic.Code.OWNED_LAUNCHER_PENDING),
                WebStopDiagnostic.of(WebStopDiagnostic.Code.PENDING_EXIT),
                WebStopDiagnostic.of(WebStopDiagnostic.Code.EXIT_UNCONFIRMED),
                WebStopDiagnostic.of(WebStopDiagnostic.Code.PRE_EXEC_WAIT),
                WebStopDiagnostic.of(WebStopDiagnostic.Code.OWNED_WEB_SCAN_UNCONFIRMED),
                WebStopDiagnostic.of(WebStopDiagnostic.Code.INSPECTION_UNCONFIRMED),
                WebStopDiagnostic.of(WebStopDiagnostic.Code.TRIAL_UNCONFIRMED))));
    for (WebStopDiagnostic.Code terminal :
        List.of(
            WebStopDiagnostic.Code.IDENTITY_CHANGED,
            WebStopDiagnostic.Code.SIGNAL_DENIED,
            WebStopDiagnostic.Code.ROOT_LINK,
            WebStopDiagnostic.Code.ROOT_FOREIGN_UID,
            WebStopDiagnostic.Code.STOP_FAILED,
            WebStopDiagnostic.Code.STOP_INTERRUPTED,
            WebStopDiagnostic.Code.TRIAL_IDENTITY_CHANGED)) {
      assertFalse(
          "terminal fact must not wait: " + terminal,
          WebStopBudget.mayStillExit(List.of(WebStopDiagnostic.of(terminal))));
    }
    // 混合结论里只要有一条是终态就不等：等不会让身份/记录错误变好。
    assertFalse(
        WebStopBudget.mayStillExit(
            List.of(
                WebStopDiagnostic.of(WebStopDiagnostic.Code.OWNED_WEB_REMAINS),
                WebStopDiagnostic.of(WebStopDiagnostic.Code.IDENTITY_CHANGED))));
    assertFalse(WebStopBudget.mayStillExit(List.of()));
    assertFalse(WebStopBudget.mayStillExit(null));
  }

  @Test
  public void remainingIsClampedToTheWindow() {
    assertEquals(8_000, WebStopBudget.remaining(1_000, 1_000, 8_000));
    assertEquals(3_000, WebStopBudget.remaining(1_000, 6_000, 8_000));
    assertEquals(0, WebStopBudget.remaining(1_000, 9_000, 8_000));
    assertEquals(0, WebStopBudget.remaining(1_000, 1_000, 0));
    assertEquals(8_000, WebStopBudget.remaining(9_000, 1_000, 8_000));
  }
}
