package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 收尾顺序锁定：优雅停止未确认才强杀本轮 guest，仍失败必须留下可导出诊断。 */
public final class TrialGuestCleanupTest {
  private static final class Io implements TrialGuestCleanup.Io {
    String firstStop = "", retryStop = "";
    String forced = "";
    int stops, forces, awaits, destroys;
    boolean launcherAlive = true, destroyReleasesLauncher = true, diagnosticFails;
    final List<Long> awaitMillis = new ArrayList<>();
    final List<String> diagnostics = new ArrayList<>();
    IOException stopFailure;

    public String stop() throws IOException {
      stops++;
      if (stopFailure != null) throw stopFailure;
      return stops == 1 ? firstStop : retryStop;
    }

    public String force() {
      forces++;
      return forced;
    }

    public boolean launcherExited() {
      return !launcherAlive;
    }

    public void awaitLauncher(long millis) {
      awaits++;
      awaitMillis.add(millis);
    }

    public void destroyLauncher() {
      destroys++;
      if (destroyReleasesLauncher) launcherAlive = false;
    }

    public void diagnostic(String detail) {
      if (diagnosticFails) throw new IllegalStateException("diagnostic sink broken");
      diagnostics.add(detail);
    }
  }

  @Test
  public void gracefulStopNeverForcesAndCleansRetainedLauncher() throws Exception {
    Io io = new Io();
    io.firstStop = "";
    assertEquals("", TrialGuestCleanup.stop(io));
    assertEquals(0, io.forces);
    assertEquals(1, io.stops);
    assertEquals(List.of(3000L, 1000L), io.awaitMillis);
    assertEquals(1, io.destroys);
    assertTrue(io.diagnostics.isEmpty());
  }

  @Test
  public void unconfirmedStopForcesOnlyAfterLauncherWindowThenRetries() throws Exception {
    Io io = new Io();
    io.firstStop = "WEB_GUESTS_UNCONFIRMED";
    io.retryStop = "";
    io.launcherAlive = false;
    assertEquals("", TrialGuestCleanup.stop(io));
    assertEquals(1, io.forces);
    assertEquals(2, io.stops);
    assertEquals(List.of(3000L), io.awaitMillis);
    assertTrue(io.diagnostics.isEmpty());
  }

  @Test
  public void forceStillUnconfirmedKeepsRecordAndWritesOneDiagnostic() throws Exception {
    Io io = new Io();
    io.firstStop = "WEB_GUESTS_UNCONFIRMED";
    io.retryStop = "WEB_GUESTS_UNCONFIRMED";
    io.forced = "WEB_STOP_TRIAL_UNCONFIRMED";
    io.launcherAlive = false;
    String detail = TrialGuestCleanup.stop(io);
    assertTrue(detail.contains("stop=WEB_GUESTS_UNCONFIRMED"));
    assertTrue(detail.contains("force=WEB_STOP_TRIAL_UNCONFIRMED"));
    assertTrue(detail.contains("retry=WEB_GUESTS_UNCONFIRMED"));
    assertEquals(List.of(detail), io.diagnostics);
    assertEquals(2, io.stops);
  }

  @Test
  public void retainedLauncherAloneIsNotAStopFailure() throws Exception {
    Io io = new Io();
    io.firstStop = "";
    io.destroyReleasesLauncher = false;
    assertEquals("", TrialGuestCleanup.stop(io));
    assertEquals(1, io.destroys);
    assertTrue(io.diagnostics.isEmpty());
  }

  @Test
  public void commandLineDetailIsRedactedBeforeItReachesDiagnostics() throws Exception {
    Io io = new Io();
    io.firstStop = "WEB_GUESTS_UNCONFIRMED token=abc123secret";
    io.retryStop = "WEB_GUESTS_UNCONFIRMED";
    io.forced = "WEB_STOP_FAILED";
    String detail = TrialGuestCleanup.stop(io);
    assertTrue(detail.contains("token=***"));
    assertFalse(detail.contains("abc123secret"));
    assertEquals(1, io.diagnostics.size());
  }

  @Test
  public void platformStopFailurePropagatesWithoutSignallingAnything() throws Exception {
    Io io = new Io();
    io.stopFailure = new IOException("TRIAL_RECORD_DIRECTORY");
    IOException error = assertThrows(IOException.class, () -> TrialGuestCleanup.stop(io));
    assertEquals("TRIAL_RECORD_DIRECTORY", error.getMessage());
    assertEquals(0, io.forces);
    assertTrue(io.diagnostics.isEmpty());
  }

  @Test
  public void diagnosticFailureCannotReplaceTheStopConclusion() throws Exception {
    Io io = new Io();
    io.diagnosticFails = true;
    io.firstStop = "WEB_GUESTS_UNCONFIRMED";
    io.retryStop = "WEB_GUESTS_UNCONFIRMED";
    assertTrue(TrialGuestCleanup.stop(io).contains("stop=WEB_GUESTS_UNCONFIRMED"));
  }
}
