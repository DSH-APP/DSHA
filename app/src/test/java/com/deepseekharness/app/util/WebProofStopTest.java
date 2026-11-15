package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.Test;

/** 残留 Web 的回收编排：只有完整出生身份两次一致才发信号，核验不过绝不结束进程。 */
public final class WebProofStopTest {
  /** 同一个夹具同时扮演观察、时钟与信号：观察序列由测试决定，信号只计数。 */
  private static final class Fixture implements WebProofStop.Probe, WebProofStop.Clock {
    final Deque<WebProofStop.Observation> observations = new ArrayDeque<>();
    WebProofStop.Observation fallback = WebProofStop.Observation.LIVE;
    WebProofStop.Signal killResult = WebProofStop.Signal.SENT;
    int terms, kills;
    long clock;

    public WebProofStop.Observation observe() {
      return observations.isEmpty() ? fallback : observations.removeFirst();
    }

    public long now() {
      return clock;
    }

    public void pause(long millis) {
      clock += millis;
    }

    WebProofStop.Signal term() {
      terms++;
      return WebProofStop.Signal.SENT;
    }

    WebProofStop.Signal kill() {
      kills++;
      return killResult;
    }
  }

  @Test
  public void unknownOrExitedRecordNeverSignals() {
    var unknown = new Fixture();
    unknown.fallback = WebProofStop.Observation.UNKNOWN;
    var rejected = WebProofStop.run(true, unknown, unknown::term, unknown::kill, unknown);
    assertFalse(rejected.stopped());
    assertEquals(WebStopDiagnostic.Code.RECORDED_SESSION_UNCONFIRMED, rejected.diagnostic().code());
    assertEquals(0, unknown.terms);
    assertEquals(0, unknown.kills);

    var exited = new Fixture();
    exited.fallback = WebProofStop.Observation.EXITED;
    var gone = WebProofStop.run(true, exited, exited::term, exited::kill, exited);
    assertTrue(gone.stopped());
    assertNull(gone.diagnostic());
    assertEquals(0, exited.terms);
    assertEquals(0, exited.kills);
  }

  @Test
  public void verifiedGuestIsAskedToExitAndGracefulExitNeverKills() {
    var fixture = new Fixture();
    fixture.observations.add(WebProofStop.Observation.LIVE);
    fixture.observations.add(WebProofStop.Observation.LIVE);
    fixture.observations.add(WebProofStop.Observation.LIVE);
    fixture.observations.add(WebProofStop.Observation.EXITED);
    var outcome = WebProofStop.run(true, fixture, fixture::term, fixture::kill, fixture);
    assertTrue(outcome.stopped());
    assertEquals(1, fixture.terms);
    assertEquals(0, fixture.kills);
  }

  @Test
  public void unconfirmedExitEscalatesToExactlyOneVerifiedKill() {
    var fixture = new Fixture();
    final boolean[] killed = {false};
    WebProofStop.Probe probe =
        () -> killed[0] ? WebProofStop.Observation.EXITED : WebProofStop.Observation.LIVE;
    WebProofStop.SignalSender kill =
        () -> {
          fixture.kills++;
          killed[0] = true;
          return fixture.killResult;
        };
    var outcome = WebProofStop.run(true, probe, fixture::term, kill, fixture);
    assertTrue(outcome.stopped());
    assertEquals(1, fixture.terms);
    assertEquals(1, fixture.kills);
  }

  @Test
  public void automaticOrUnverifiableForceNeverSignals() {
    var noForce = new Fixture();
    var pending = WebProofStop.run(false, noForce, noForce::term, noForce::kill, noForce);
    assertFalse(pending.stopped());
    assertEquals(WebStopDiagnostic.Code.PENDING_EXIT, pending.diagnostic().code());
    assertEquals(1, noForce.terms);
    assertEquals(0, noForce.kills);

    var changed = new Fixture();
    final int[] reads = {0};
    WebProofStop.Probe probe =
        () -> {
          reads[0]++;
          // 普通停止前的两次核验 + 等待中的一次仍在运行；随后进程身份不可读。
          return reads[0] <= 3 ? WebProofStop.Observation.LIVE : WebProofStop.Observation.UNKNOWN;
        };
    var identity = WebProofStop.run(true, probe, changed::term, changed::kill, changed);
    assertFalse(identity.stopped());
    assertEquals(WebStopDiagnostic.Code.IDENTITY_CHANGED, identity.diagnostic().code());
    assertEquals(0, changed.kills);

    var denied = new Fixture();
    denied.killResult = WebProofStop.Signal.DENIED;
    WebProofStop.SignalSender deniedTerm = () -> WebProofStop.Signal.DENIED;
    var rejected = WebProofStop.run(true, denied, deniedTerm, denied::kill, denied);
    assertFalse(rejected.stopped());
    assertEquals(WebStopDiagnostic.Code.SIGNAL_DENIED, rejected.diagnostic().code());
    assertEquals(0, denied.kills);
  }

  @Test
  public void retiringLauncherWaitsForGuestExitAndUsesVerifiedSignals() {
    var guest = new Fixture();
    guest.observations.add(WebProofStop.Observation.LIVE);
    guest.observations.add(WebProofStop.Observation.LIVE);
    guest.observations.add(WebProofStop.Observation.EXITED);
    var launcher = new Fixture();
    launcher.observations.add(WebProofStop.Observation.LIVE);
    launcher.observations.add(WebProofStop.Observation.LIVE);
    launcher.observations.add(WebProofStop.Observation.EXITED);
    var outcome =
        WebProofStop.runRetiringLauncher(
            true, guest, guest::term, guest::kill, launcher, launcher::term, launcher::kill, guest);
    assertTrue(outcome.stopped());
    assertEquals(1, guest.terms);
    assertEquals(0, guest.kills);
    assertEquals(1, launcher.terms);
    assertEquals(0, launcher.kills);
  }

  @Test
  public void launcherIsNeverTouchedWhenTheGuestExitIsUnconfirmed() {
    var guest = new Fixture();
    guest.fallback = WebProofStop.Observation.LIVE;
    var launcher = new Fixture();
    final boolean[] probed = {false};
    WebProofStop.Probe launcherProbe =
        () -> {
          probed[0] = true;
          return WebProofStop.Observation.LIVE;
        };
    var outcome =
        WebProofStop.runRetiringLauncher(
            true,
            guest,
            guest::term,
            guest::kill,
            launcherProbe,
            launcher::term,
            launcher::kill,
            guest);
    assertFalse(outcome.stopped());
    assertEquals(WebStopDiagnostic.Code.EXIT_UNCONFIRMED, outcome.diagnostic().code());
    assertEquals(1, guest.kills);
    assertFalse(probed[0]);
    assertEquals(0, launcher.terms);
    assertEquals(0, launcher.kills);
  }

  @Test
  public void unverifiedPreviousLauncherIsNeverSignalled() {
    var guest = new Fixture();
    guest.observations.add(WebProofStop.Observation.EXITED);
    var launcher = new Fixture();
    launcher.fallback = WebProofStop.Observation.UNKNOWN;
    var outcome =
        WebProofStop.runRetiringLauncher(
            true, guest, guest::term, guest::kill, launcher, launcher::term, launcher::kill, guest);
    assertFalse(outcome.stopped());
    assertEquals(WebStopDiagnostic.Code.RECORDED_SESSION_UNCONFIRMED, outcome.diagnostic().code());
    assertEquals(0, guest.terms);
    assertEquals(0, launcher.terms);
    assertEquals(0, launcher.kills);
  }

  @Test
  public void previousLauncherIdentityChangeIsNeverRetried() {
    var guest = new Fixture();
    guest.observations.add(WebProofStop.Observation.EXITED);
    var launcher = new Fixture();
    launcher.observations.add(WebProofStop.Observation.LIVE);
    launcher.observations.add(WebProofStop.Observation.LIVE);
    launcher.observations.add(WebProofStop.Observation.UNKNOWN);
    var outcome =
        WebProofStop.runRetiringLauncher(
            true, guest, guest::term, guest::kill, launcher, launcher::term, launcher::kill, guest);
    assertFalse(outcome.stopped());
    assertEquals(WebStopDiagnostic.Code.IDENTITY_CHANGED, outcome.diagnostic().code());
    assertEquals(1, launcher.terms);
    assertEquals(0, launcher.kills);
  }

  @Test
  public void prootAndProrootCommandsMatchOnlyTheSavedBirth() {
    String boot = "12345678-1234-1234-1234-123456789abc";
    String instance = "23456789-1234-1234-1234-123456789abc";
    String nonce = "ab".repeat(32);
    String proot =
        String.join(
            "\0",
            "node",
            "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
            "web",
            "--no-open",
            "--host",
            "127.0.0.1",
            "--port",
            "0",
            "");
    String proroot =
        "libproroot-bridge.so /data/app/pkg/lib/arm64/libproroot-linker.so --argv0 node "
            + "--preload /data/app/pkg/lib/arm64/libproroot-runtime.so "
            + "/data/data/com.dsh.client/files/linux/ubuntu/usr/local/bin/node "
            + "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080"
            + "\0";
    proroot = proroot.replace(' ', '\0');
    for (String command : new String[] {proot, proroot}) {
      assertTrue(command, WebProcSel.maySignalWeb(command));
      var saved =
          new WebSessionProof.Saved(
              boot, 7, instance, nonce, 101, 1234, command, 100, 1233, command);
      var web = new WebSessionProof.Process(101, 1234, 10001, command, "7", instance, nonce, true);
      var launcher =
          new WebSessionProof.Process(100, 1233, 10001, command, "7", instance, nonce, true);
      assertTrue(WebSessionProof.matches(saved, boot, 10001, web, launcher));
      var reused =
          new WebSessionProof.Process(101, 1235, 10001, command, "7", instance, nonce, true);
      assertFalse(WebSessionProof.matches(saved, boot, 10001, reused, launcher));
    }
  }
}
