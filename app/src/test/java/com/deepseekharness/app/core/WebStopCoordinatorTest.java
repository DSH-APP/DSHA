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
}
