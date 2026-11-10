package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.runtime.StoredWebSession;
import com.deepseekharness.app.runtime.WebProcessManager.SessionObservation;
import com.deepseekharness.app.util.DshAuthSession;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class WebSessionRecoveryTest {
  private static class Fixture implements WebSessionRecovery.Ports {
    final List<String> calls = new ArrayList<>();
    final List<String> progress = new ArrayList<>();
    final List<Integer> pauses = new ArrayList<>();
    final List<String> failures = new ArrayList<>();
    boolean current = true, compatible = true, valid = true, authenticated = true, deny;
    int probes, authCalls;
    StoredWebSession.Record record = new StoredWebSession.Record(null, "", "", "", "", false, "");

    public StoredWebSession.Record read() throws Exception {
      calls.add("read");
      return record;
    }

    public boolean current() {
      return current;
    }

    public boolean compatible(StoredWebSession.Record r) throws Exception {
      calls.add("compatible");
      return compatible;
    }

    public boolean verify(StoredWebSession.Record r) throws Exception {
      calls.add("verify");
      probes++;
      if (deny) throw new IOException("PROC_DENIED");
      return valid;
    }

    public boolean authenticate(StoredWebSession.Record r) throws Exception {
      calls.add("http");
      authCalls++;
      return authenticated;
    }

    public boolean adopt(StoredWebSession.Record r) throws Exception {
      calls.add("adopt");
      return true;
    }

    public void pauseBeforeRetry(int attempt) throws InterruptedException {
      calls.add("pause");
      pauses.add(attempt);
    }

    void defaultPause(int attempt) throws InterruptedException {
      WebSessionRecovery.Ports.super.pauseBeforeRetry(attempt);
    }

    public void progress(String reason, int attempt) {
      progress.add(reason + ":" + attempt);
    }

    public void unavailable(String stage, Exception failure) {
      failures.add(stage);
    }
  }

  private static class ObservedFixture extends Fixture {
    final List<SessionObservation> observations = new ArrayList<>();
    SessionObservation observed = SessionObservation.LIVE;
    boolean launcherGone, retired = true;
    int observationCalls, launcherCalls, retireCalls;
    String stopAt = "", failAt = "";

    void checkpoint(String stage) throws IOException {
      if (stopAt.equals(stage)) current = false;
      if (failAt.equals(stage)) throw new IOException("sensitive-message-must-not-be-a-reason");
    }

    public StoredWebSession.Record read() throws Exception {
      var value = super.read();
      checkpoint("read");
      return value;
    }

    public SessionObservation observe(StoredWebSession.Record r) throws Exception {
      assertSame(record, r);
      calls.add("observe");
      observationCalls++;
      checkpoint("observe");
      checkpoint("observe-" + observationCalls);
      return observations.isEmpty() ? observed : observations.remove(0);
    }

    public boolean compatible(StoredWebSession.Record r) throws Exception {
      boolean result = super.compatible(r);
      checkpoint("compatible");
      return result;
    }

    public boolean authenticate(StoredWebSession.Record r) throws Exception {
      boolean result = super.authenticate(r);
      checkpoint("http");
      return result;
    }

    public boolean launcherExited(StoredWebSession.Record r) throws Exception {
      assertSame(record, r);
      calls.add("launcher");
      launcherCalls++;
      checkpoint("launcher");
      return launcherGone;
    }

    public boolean retire(StoredWebSession.Record r) throws Exception {
      assertSame(record, r);
      calls.add("retire");
      retireCalls++;
      checkpoint("retire");
      return retired;
    }

    public boolean adopt(StoredWebSession.Record r) throws Exception {
      boolean result = super.adopt(r);
      checkpoint("adopt");
      return result;
    }
  }

  private static void assertOutcome(
      WebSessionRecovery.Result result,
      String reason,
      int attempts,
      WebSessionRecovery.Outcome value) {
    assertEquals(result, value.result());
    assertEquals(reason, value.reason());
    assertEquals(attempts, value.attempts());
  }

  @Test
  public void noRecordDoesNotArmWorkOrTouchBackend() {
    Fixture f = new Fixture();
    f.record = null;
    assertOutcome(
        WebSessionRecovery.Result.NONE, "NO_RECORD", 0, WebSessionRecovery.recoverDetailed(f));
    assertEquals(List.of("read"), f.calls);
  }

  @Test
  public void legacyVerifyStillRequiresTwoKernelChecksAndHttpToAdopt() {
    Fixture f = new Fixture();
    assertEquals(WebSessionRecovery.Result.RESTORED, WebSessionRecovery.recover(f));
    assertEquals(List.of("read", "verify", "compatible", "http", "verify", "adopt"), f.calls);
  }

  @Test
  public void productionObservationOverridesLegacyVerifyAndRechecksAfterHttp() {
    ObservedFixture f = new ObservedFixture();
    f.valid = false;
    assertOutcome(
        WebSessionRecovery.Result.RESTORED, "RESTORED", 1, WebSessionRecovery.recoverDetailed(f));
    assertEquals(List.of("read", "observe", "compatible", "http", "observe", "adopt"), f.calls);
    assertEquals(List.of("AUTHENTICATING:1", "RESTORED:1"), f.progress);
    assertEquals(0, f.probes);
  }

  @Test
  public void procDeniedAndLegacyFalseRemainUnknownWithoutHttpOrAdoption() {
    for (boolean denied : new boolean[] {true, false}) {
      Fixture f = new Fixture();
      f.deny = denied;
      f.valid = false;
      assertOutcome(
          WebSessionRecovery.Result.UNKNOWN,
          "IDENTITY_UNKNOWN",
          0,
          WebSessionRecovery.recoverDetailed(f));
      assertEquals(List.of("read", "verify"), f.calls);
    }
  }

  @Test
  public void unknownOrMissingObservationCannotUseBooleanVerifyOrRetire() {
    for (SessionObservation observation :
        new SessionObservation[] {SessionObservation.UNKNOWN, null}) {
      ObservedFixture f = new ObservedFixture();
      f.observed = observation;
      f.launcherGone = true;
      assertOutcome(
          WebSessionRecovery.Result.UNKNOWN,
          "IDENTITY_UNKNOWN",
          0,
          WebSessionRecovery.recoverDetailed(f));
      assertEquals(List.of("read", "observe"), f.calls);
    }
  }

  @Test
  public void unresolvedIdentityKeepsTheFailingStageForExportableDiagnostics() {
    ObservedFixture f = new ObservedFixture();
    f.observed = SessionObservation.UNKNOWN;
    WebSessionRecovery.Outcome outcome = WebSessionRecovery.recoverDetailed(f);
    assertOutcome(WebSessionRecovery.Result.UNKNOWN, "IDENTITY_UNKNOWN", 0, outcome);
    assertEquals("KERNEL_VERIFY", outcome.stage());
    assertEquals(List.of("read", "observe"), f.calls);
    assertFalse(f.calls.contains("http"));
    assertFalse(f.calls.contains("retire"));
  }

  @Test
  public void failedRetirementKeepsTheRetireStageWithoutFabricatingExit() {
    ObservedFixture f = new ObservedFixture();
    f.observed = SessionObservation.EXITED;
    f.launcherGone = true;
    f.retired = false;
    WebSessionRecovery.Outcome outcome = WebSessionRecovery.recoverDetailed(f);
    assertOutcome(WebSessionRecovery.Result.UNKNOWN, "RETIRE_UNAVAILABLE", 0, outcome);
    assertEquals("RETIRE", outcome.stage());
    assertEquals(List.of("read", "observe", "launcher", "retire"), f.calls);
  }

  @Test
  public void failedKernelReadReportsOnlyItsStageAndDiagnosticFailureCannotGrantAdoption() {
    Fixture f =
        new Fixture() {
          public void unavailable(String stage, Exception failure) {
            super.unavailable(stage, failure);
            throw new IllegalStateException("diagnostic sink unavailable");
          }
        };
    f.deny = true;
    assertOutcome(
        WebSessionRecovery.Result.UNKNOWN,
        "IDENTITY_UNKNOWN",
        0,
        WebSessionRecovery.recoverDetailed(f));
    assertEquals(List.of("KERNEL_VERIFY"), f.failures);
    assertFalse(f.calls.contains("adopt"));
  }

  @Test
  public void incompatibleLiveRecordDoesNotAuthenticateOrRetry() {
    ObservedFixture f = new ObservedFixture();
    f.compatible = false;
    assertOutcome(
        WebSessionRecovery.Result.UNKNOWN,
        "INCOMPATIBLE",
        0,
        WebSessionRecovery.recoverDetailed(f));
    assertEquals(List.of("read", "observe", "compatible"), f.calls);
  }

  @Test
  public void guestExitAloneKeepsLauncherBarrierWithoutHttpOrRetirement() {
    ObservedFixture f = new ObservedFixture();
    f.observed = SessionObservation.EXITED;
    assertOutcome(
        WebSessionRecovery.Result.UNKNOWN,
        "LAUNCHER_PENDING",
        0,
        WebSessionRecovery.recoverDetailed(f));
    assertEquals(List.of("read", "observe", "launcher"), f.calls);
    assertEquals(0, f.retireCalls);
  }

  @Test
  public void onlyConfirmedGuestAndLauncherExitCanRetireEvenAcrossPackageUpgrade() {
    ObservedFixture f = new ObservedFixture();
    f.observed = SessionObservation.EXITED;
    f.launcherGone = true;
    f.compatible = false;
    assertOutcome(
        WebSessionRecovery.Result.EXITED,
        "EXIT_CONFIRMED",
        0,
        WebSessionRecovery.recoverDetailed(f));
    assertEquals(List.of("read", "observe", "launcher", "retire"), f.calls);
    assertEquals(1, f.retireCalls);
  }

  @Test
  public void failedIdentityBoundRetirementRemainsUnknownOrStale() {
    for (boolean stale : new boolean[] {false, true}) {
      ObservedFixture f = new ObservedFixture();
      f.observed = SessionObservation.EXITED;
      f.launcherGone = true;
      f.retired = false;
      if (stale) f.stopAt = "retire";
      assertOutcome(
          stale ? WebSessionRecovery.Result.STALE : WebSessionRecovery.Result.UNKNOWN,
          stale ? "STALE" : "RETIRE_UNAVAILABLE",
          0,
          WebSessionRecovery.recoverDetailed(f));
      assertEquals(1, f.retireCalls);
      assertTrue(f.pauses.isEmpty());
    }
  }

  @Test
  public void successfulRetirementCanInvalidateItsOwnRecoveryGeneration() {
    ObservedFixture f = new ObservedFixture();
    f.observed = SessionObservation.EXITED;
    f.launcherGone = true;
    f.stopAt = "retire";
    assertOutcome(
        WebSessionRecovery.Result.EXITED,
        "EXIT_CONFIRMED",
        0,
        WebSessionRecovery.recoverDetailed(f));
  }

  @Test
  public void unavailableLauncherOrRetirementNeverFabricatesExitOrRetriesMutation() {
    for (String stage : List.of("launcher", "retire")) {
      ObservedFixture f = new ObservedFixture();
      f.observed = SessionObservation.EXITED;
      f.launcherGone = true;
      f.failAt = stage;
      assertOutcome(
          WebSessionRecovery.Result.UNKNOWN,
          stage.equals("launcher") ? "LAUNCHER_UNKNOWN" : "RETIRE_UNAVAILABLE",
          0,
          WebSessionRecovery.recoverDetailed(f));
      assertEquals(stage.equals("launcher") ? 0 : 1, f.retireCalls);
      assertTrue(f.pauses.isEmpty());
    }
  }

  @Test
  public void authenticationCanRecoverOnEachOfThreeAttemptsWithoutReplayingAdoption() {
    for (int success = 1; success <= 3; success++) {
      final int readyAt = success;
      ObservedFixture f =
          new ObservedFixture() {
            public boolean authenticate(StoredWebSession.Record r) throws Exception {
              super.authenticate(r);
              return authCalls == readyAt;
            }
          };
      assertOutcome(
          WebSessionRecovery.Result.RESTORED,
          "RESTORED",
          success,
          WebSessionRecovery.recoverDetailed(f));
      assertEquals(success, f.authCalls);
      assertEquals(success * 2, f.observationCalls);
      assertEquals(success - 1, f.pauses.size());
      assertEquals(1, java.util.Collections.frequency(f.calls, "adopt"));
      assertEquals(0, f.retireCalls);
    }
  }

  @Test
  public void unavailableAuthIsLimitedToThreeAttemptsAndTwoBoundedIntervals() {
    ObservedFixture f = new ObservedFixture();
    f.authenticated = false;
    assertOutcome(
        WebSessionRecovery.Result.UNKNOWN,
        "AUTH_UNAVAILABLE",
        3,
        WebSessionRecovery.recoverDetailed(f));
    assertEquals(3, f.authCalls);
    assertEquals(6, f.observationCalls);
    assertEquals(List.of(1, 2), f.pauses);
    assertEquals(500, WebSessionRecovery.retryDelayMillis(1));
    assertEquals(1500, WebSessionRecovery.retryDelayMillis(2));
    assertThrows(IllegalArgumentException.class, () -> WebSessionRecovery.retryDelayMillis(3));
    assertEquals(
        List.of(
            "AUTHENTICATING:1",
            "AUTH_UNAVAILABLE:1",
            "AUTHENTICATING:2",
            "AUTH_UNAVAILABLE:2",
            "AUTHENTICATING:3",
            "AUTH_UNAVAILABLE:3"),
        f.progress);
    assertFalse(f.calls.contains("adopt"));
    assertEquals(0, f.retireCalls);
  }

  @Test
  public void transientAuthExceptionCanOnlyRecheckWhileIdentityStaysLive() {
    ObservedFixture f = new ObservedFixture();
    f.failAt = "http";
    assertOutcome(
        WebSessionRecovery.Result.UNKNOWN,
        "AUTH_UNAVAILABLE",
        3,
        WebSessionRecovery.recoverDetailed(f));
    assertEquals(List.of("AUTHENTICATE", "AUTHENTICATE", "AUTHENTICATE"), f.failures);
    assertEquals(List.of(1, 2), f.pauses);
    assertEquals(0, f.retireCalls);
  }

  @Test
  public void lostIdentityAfterEitherAuthResultBlocksBothAdoptionAndRetry() {
    for (boolean authenticated : new boolean[] {true, false}) {
      ObservedFixture f = new ObservedFixture();
      f.authenticated = authenticated;
      f.observations.addAll(List.of(SessionObservation.LIVE, SessionObservation.UNKNOWN));
      assertOutcome(
          WebSessionRecovery.Result.UNKNOWN,
          "IDENTITY_UNKNOWN",
          1,
          WebSessionRecovery.recoverDetailed(f));
      assertEquals(1, f.authCalls);
      assertTrue(f.pauses.isEmpty());
      assertFalse(f.calls.contains("adopt"));
      assertEquals(0, f.retireCalls);
    }
  }

  @Test
  public void exitAfterEitherAuthResultStillRequiresBothExitProofs() {
    for (boolean authenticated : new boolean[] {true, false}) {
      for (boolean launcherGone : new boolean[] {true, false}) {
        ObservedFixture f = new ObservedFixture();
        f.authenticated = authenticated;
        f.launcherGone = launcherGone;
        f.observations.addAll(List.of(SessionObservation.LIVE, SessionObservation.EXITED));
        assertOutcome(
            launcherGone ? WebSessionRecovery.Result.EXITED : WebSessionRecovery.Result.UNKNOWN,
            launcherGone ? "EXIT_CONFIRMED" : "LAUNCHER_PENDING",
            1,
            WebSessionRecovery.recoverDetailed(f));
        assertEquals(launcherGone ? 1 : 0, f.retireCalls);
        assertTrue(f.pauses.isEmpty());
        assertFalse(f.calls.contains("adopt"));
      }
    }
  }

  @Test
  public void nextAttemptMustReobserveActualIdentityAfterPause() {
    for (SessionObservation next :
        new SessionObservation[] {SessionObservation.UNKNOWN, SessionObservation.EXITED}) {
      ObservedFixture f = new ObservedFixture();
      f.authenticated = false;
      f.launcherGone = true;
      f.observations.addAll(List.of(SessionObservation.LIVE, SessionObservation.LIVE, next));
      assertOutcome(
          next == SessionObservation.EXITED
              ? WebSessionRecovery.Result.EXITED
              : WebSessionRecovery.Result.UNKNOWN,
          next == SessionObservation.EXITED ? "EXIT_CONFIRMED" : "IDENTITY_UNKNOWN",
          1,
          WebSessionRecovery.recoverDetailed(f));
      assertEquals(1, f.authCalls);
      assertEquals(List.of(1), f.pauses);
    }
  }

  @Test
  public void stopOrGenerationChangeAtEveryReadBoundaryPreventsFurtherEffects() {
    for (String stage :
        List.of("read", "observe-1", "compatible", "http", "observe-2", "launcher")) {
      ObservedFixture f = new ObservedFixture();
      f.stopAt = stage;
      if (stage.equals("launcher")) {
        f.observed = SessionObservation.EXITED;
        f.launcherGone = true;
      }
      assertOutcome(
          WebSessionRecovery.Result.STALE,
          "STALE",
          stage.equals("http") || stage.equals("observe-2") ? 1 : 0,
          WebSessionRecovery.recoverDetailed(f));
      assertFalse(stage, f.calls.contains("adopt"));
      assertEquals(stage, 0, f.retireCalls);
      assertTrue(stage, f.pauses.isEmpty());
    }
  }

  @Test
  public void stopDuringReadWinsEvenWhenNoSavedRecordExists() {
    ObservedFixture f = new ObservedFixture();
    f.record = null;
    f.stopAt = "read";
    assertOutcome(
        WebSessionRecovery.Result.STALE, "STALE", 0, WebSessionRecovery.recoverDetailed(f));
    assertEquals(List.of("read"), f.calls);
  }

  @Test
  public void earlierStopStillLoadsReadOnlyEvidenceBeforeRejectingAdoption() {
    Fixture f = new Fixture();
    f.current = false;
    assertEquals(WebSessionRecovery.Result.STALE, WebSessionRecovery.recover(f));
    assertEquals(List.of("read"), f.calls);
  }

  @Test
  public void progressStopBeforeAuthOrPauseIsCheckedBeforeWork() {
    for (String stopReason : List.of("AUTHENTICATING", "AUTH_UNAVAILABLE")) {
      ObservedFixture f =
          new ObservedFixture() {
            public void progress(String reason, int attempt) {
              super.progress(reason, attempt);
              if (reason.equals(stopReason)) current = false;
            }
          };
      f.authenticated = false;
      assertOutcome(
          WebSessionRecovery.Result.STALE,
          "STALE",
          stopReason.equals("AUTHENTICATING") ? 0 : 1,
          WebSessionRecovery.recoverDetailed(f));
      assertTrue(f.pauses.isEmpty());
      assertFalse(f.calls.contains("adopt"));
    }
  }

  @Test
  public void stopAndGenerationChangeDuringPausePreventAnotherProbeOrAuth() {
    for (boolean changeGeneration : new boolean[] {true, false}) {
      ObservedFixture f =
          new ObservedFixture() {
            int generation = 7;

            public boolean current() {
              return current && generation == 7;
            }

            public void pauseBeforeRetry(int attempt) throws InterruptedException {
              super.pauseBeforeRetry(attempt);
              if (changeGeneration) generation++;
              else current = false;
            }
          };
      f.authenticated = false;
      assertOutcome(
          WebSessionRecovery.Result.STALE, "STALE", 1, WebSessionRecovery.recoverDetailed(f));
      assertEquals(2, f.observationCalls);
      assertEquals(1, f.authCalls);
      assertEquals(List.of(1), f.pauses);
    }
  }

  @Test
  public void exceptionAfterStopIsStaleAndDoesNotRetireOrRetry() {
    for (String stage : List.of("read", "observe", "compatible", "http", "launcher", "retire")) {
      ObservedFixture f = new ObservedFixture();
      f.stopAt = f.failAt = stage;
      if (stage.equals("launcher") || stage.equals("retire")) {
        f.observed = SessionObservation.EXITED;
        f.launcherGone = true;
      }
      assertOutcome(
          WebSessionRecovery.Result.STALE,
          "STALE",
          stage.equals("http") ? 1 : 0,
          WebSessionRecovery.recoverDetailed(f));
      assertTrue(f.pauses.isEmpty());
      assertFalse(f.calls.contains("adopt"));
    }
  }

  @Test
  public void exceptionsHaveStableNonSensitiveReasonsAndNeverFabricateExit() {
    String[][] stages = {
      {"read", "RECORD_UNAVAILABLE"},
      {"observe-1", "IDENTITY_UNKNOWN"},
      {"compatible", "INCOMPATIBLE"},
      {"observe-2", "IDENTITY_UNKNOWN"},
      {"adopt", "ADOPT_UNAVAILABLE"}
    };
    for (String[] stage : stages) {
      ObservedFixture f = new ObservedFixture();
      f.failAt = stage[0];
      assertOutcome(
          WebSessionRecovery.Result.UNKNOWN,
          stage[1],
          stage[0].equals("observe-2") || stage[0].equals("adopt") ? 1 : 0,
          WebSessionRecovery.recoverDetailed(f));
      assertEquals(0, f.retireCalls);
      assertTrue(f.pauses.isEmpty());
      assertFalse(f.progress.toString().contains("sensitive-message"));
    }
  }

  @Test
  public void progressFailureCannotChangeSuccessfulOrUnknownOutcome() {
    for (boolean authenticated : new boolean[] {true, false}) {
      ObservedFixture f =
          new ObservedFixture() {
            public void progress(String reason, int attempt) {
              throw new IllegalStateException("diagnostic sink unavailable");
            }
          };
      f.authenticated = authenticated;
      assertOutcome(
          authenticated ? WebSessionRecovery.Result.RESTORED : WebSessionRecovery.Result.UNKNOWN,
          authenticated ? "RESTORED" : "AUTH_UNAVAILABLE",
          authenticated ? 1 : 3,
          WebSessionRecovery.recoverDetailed(f));
    }
  }

  @Test
  public void successfulAdoptionMayAdvanceGenerationButFailedAdoptionIsNeverRetried() {
    ObservedFixture accepted = new ObservedFixture();
    accepted.stopAt = "adopt";
    assertOutcome(
        WebSessionRecovery.Result.RESTORED,
        "RESTORED",
        1,
        WebSessionRecovery.recoverDetailed(accepted));
    ObservedFixture rejected =
        new ObservedFixture() {
          public boolean adopt(StoredWebSession.Record r) throws Exception {
            super.adopt(r);
            return false;
          }
        };
    assertOutcome(
        WebSessionRecovery.Result.STALE, "STALE", 1, WebSessionRecovery.recoverDetailed(rejected));
    assertEquals(1, java.util.Collections.frequency(rejected.calls, "adopt"));
    assertTrue(rejected.pauses.isEmpty());
  }

  @Test
  public void interruptionBeforeFirstProbeDoesNotAuthenticate() {
    ObservedFixture f = new ObservedFixture();
    Thread.currentThread().interrupt();
    try {
      assertOutcome(
          WebSessionRecovery.Result.UNKNOWN,
          "INTERRUPTED",
          0,
          WebSessionRecovery.recoverDetailed(f));
      assertEquals(List.of("read"), f.calls);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void interruptedPausePreservesFlagAndNeverBeginsAnotherAttempt() {
    ObservedFixture f =
        new ObservedFixture() {
          public void pauseBeforeRetry(int attempt) throws InterruptedException {
            super.pauseBeforeRetry(attempt);
            throw new InterruptedException("cancelled");
          }
        };
    f.authenticated = false;
    try {
      assertOutcome(
          WebSessionRecovery.Result.UNKNOWN,
          "INTERRUPTED",
          1,
          WebSessionRecovery.recoverDetailed(f));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, f.authCalls);
      assertEquals(2, f.observationCalls);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void interruptedAuthenticationIsNotRetried() {
    ObservedFixture f =
        new ObservedFixture() {
          public boolean authenticate(StoredWebSession.Record r) throws Exception {
            super.authenticate(r);
            throw new InterruptedException("cancelled");
          }
        };
    try {
      assertOutcome(
          WebSessionRecovery.Result.UNKNOWN,
          "INTERRUPTED",
          1,
          WebSessionRecovery.recoverDetailed(f));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(f.pauses.isEmpty());
      assertEquals(0, f.retireCalls);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void defaultPauseIsInterruptibleWithoutRealSleepInTest() {
    Fixture f = new Fixture();
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> f.defaultPause(1));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void actualLoopbackAuthenticationMustAcceptCookieBeforeAdoption() throws Exception {
    try (ServerSocket listener =
        new ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))) {
      String name = "dsh-auth-" + "a".repeat(43);
      String cookie = name + "=v1.payload." + "b".repeat(43);
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      Thread server =
          new Thread(
              () -> {
                try {
                  for (int i = 0; i < 2; i++)
                    try (var socket = listener.accept()) {
                      var reader =
                          new java.io.BufferedReader(
                              new java.io.InputStreamReader(
                                  socket.getInputStream(), StandardCharsets.US_ASCII));
                      StringBuilder request = new StringBuilder();
                      for (String line; (line = reader.readLine()) != null && !line.isEmpty(); )
                        request.append(line).append('\n');
                      if (i == 0) assertTrue(request.toString().contains("?token="));
                      else assertTrue(request.toString().contains("Cookie: " + cookie));
                      String response =
                          i == 0
                              ? "HTTP/1.1 303 See Other\r\nLocation: ./\r\nSet-Cookie: "
                                  + cookie
                                  + "; HttpOnly\r\nContent-Length: 0\r\n\r\n"
                              : "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
                      socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    }
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      server.setDaemon(true);
      server.start();
      Fixture f =
          new Fixture() {
            public boolean authenticate(StoredWebSession.Record r) {
              calls.add("http");
              return DshAuthSession.exchange(
                      "http://127.0.0.1:" + listener.getLocalPort() + "/?token=" + "t".repeat(43),
                      listener.getLocalPort(),
                      () -> current,
                      2000)
                  .ready();
            }
          };
      assertEquals(WebSessionRecovery.Result.RESTORED, WebSessionRecovery.recover(f));
      server.join(3000);
      assertFalse(server.isAlive());
      if (failure.get() != null) throw new AssertionError(failure.get());
    }
  }
}
