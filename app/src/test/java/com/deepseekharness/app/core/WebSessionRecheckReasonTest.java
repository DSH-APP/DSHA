package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.runtime.WebProcessManager.SessionObservation;
import com.deepseekharness.app.util.DshAuthSession;
import java.io.IOException;
import org.junit.Test;

public final class WebSessionRecheckReasonTest {
  private static final class Ports implements WebSessionRecheck.Ports {
    SessionObservation state = SessionObservation.LIVE;
    boolean kernelUnavailable, authUnavailable, current = true;
    int authCalls;

    public boolean current() {
      return current;
    }

    public boolean alreadyLive() {
      return false;
    }

    public SessionObservation observe() throws Exception {
      if (kernelUnavailable)
        throw new IOException("private path and credential are not diagnostics");
      return state;
    }

    public DshAuthSession.Result authenticate() throws Exception {
      authCalls++;
      if (authUnavailable) throw new IOException("token=secret");
      return null;
    }
  }

  @Test
  public void kernelUnknownAndAuthenticationUnavailableHaveDifferentFixedReasons() {
    Ports ports = new Ports();
    ports.kernelUnavailable = true;
    var kernel = WebSessionRecheck.check(ports);
    assertEquals(WebSessionRecheck.Result.UNKNOWN, kernel.result());
    assertEquals("IDENTITY_UNKNOWN", kernel.reason());
    assertEquals(0, kernel.attempts());
    ports.kernelUnavailable = false;
    ports.authUnavailable = true;
    var authentication = WebSessionRecheck.check(ports);
    assertEquals(WebSessionRecheck.Result.UNKNOWN, authentication.result());
    assertEquals("AUTH_UNAVAILABLE", authentication.reason());
    assertEquals(1, authentication.attempts());
    assertFalse(authentication.reason().contains("secret"));
  }

  @Test
  public void exitedGuestIsReportedWithoutAuthenticatingOrRetiringItsLauncher() {
    Ports ports = new Ports();
    ports.state = SessionObservation.EXITED;
    var outcome = WebSessionRecheck.check(ports);
    assertEquals(WebSessionRecheck.Result.EXITED, outcome.result());
    assertEquals("EXIT_CONFIRMED", outcome.reason());
    assertEquals(0, ports.authCalls);
  }

  @Test
  public void failedAuthenticationDoesNotBecomeReadyOrExposeBackendDetails() {
    Ports ports = new Ports();
    var outcome = WebSessionRecheck.check(ports);
    assertEquals(WebSessionRecheck.Result.UNKNOWN, outcome.result());
    assertEquals("AUTH_UNAVAILABLE", outcome.reason());
    assertEquals(1, ports.authCalls);
    assertNull(outcome.authentication());
  }
}
