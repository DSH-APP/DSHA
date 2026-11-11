package com.deepseekharness.app.core;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebStopCoordinatorTest {
  @Test
  public void commandsSucceedButUnconfirmedExitStillBlocks() {
    var result =
        WebStopCoordinator.stop(
            new WebStopCoordinator.Ports() {
              public String stopGuest() {
                return "";
              }

              public String stopOwnedLaunchers() {
                return "";
              }

              public boolean confirm() {
                return false;
              }
            });
    assertFalse(result.stopped());
    assertEquals("WEB_PROCESS_EXIT_UNCONFIRMED", result.code());
  }

  @Test
  public void permissionErrorCanOnlyClearAfterActualFullConfirmation() {
    for (boolean confirmed : new boolean[] {false, true}) {
      var result =
          WebStopCoordinator.stop(
              new WebStopCoordinator.Ports() {
                public String stopGuest() throws IOException {
                  throw new IOException("permission denied");
                }

                public String stopOwnedLaunchers() {
                  return "";
                }

                public boolean confirm() {
                  return confirmed;
                }
              });
      assertEquals(confirmed, result.stopped());
      if (!confirmed) assertTrue(result.detail().contains("permission denied"));
    }
  }

  @Test
  public void onlyRealUnconfirmedStopConclusionsAuthorizeForceStop() {
    for (String code :
        java.util.List.of(
            "WEB_PROCESS_EXIT_UNCONFIRMED",
            "WEB_GUESTS_UNCONFIRMED",
            "WEB_RECORDED_STOP_UNCONFIRMED",
            "WEB_RESIDUAL_STOP_UNCONFIRMED",
            "WEB_START_STOP_UNCONFIRMED")) {
      assertTrue(
          code,
          new WebStopCoordinator.Result(WebStopCoordinator.Status.UNCONFIRMED, code, "")
              .unconfirmedStop());
    }
    // recordStopFailure 用 FAILED + WEB_STOP_FAILED，同样是真实失败事实。
    assertTrue(
        new WebStopCoordinator.Result(WebStopCoordinator.Status.FAILED, "WEB_STOP_FAILED", "")
            .unconfirmedStop());
    for (String code :
        java.util.List.of(
            "WEB_START_PENDING", "WEB_STOP_PENDING", "WEB_STOP_NOT_REQUESTED", "", "WEB_STOPPED")) {
      assertFalse(
          code,
          new WebStopCoordinator.Result(WebStopCoordinator.Status.UNCONFIRMED, code, "")
              .unconfirmedStop());
    }
    assertFalse(
        new WebStopCoordinator.Result(
                WebStopCoordinator.Status.STOPPED, "WEB_PROCESS_EXIT_UNCONFIRMED", "")
            .unconfirmedStop());
    assertFalse(
        new WebStopCoordinator.Result(WebStopCoordinator.Status.CANCELLED, "WEB_STOP_CANCELLED", "")
            .unconfirmedStop());
  }
}
