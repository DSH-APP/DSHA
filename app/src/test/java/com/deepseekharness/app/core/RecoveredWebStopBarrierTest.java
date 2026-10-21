package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import com.deepseekharness.app.runtime.StoredWebSession;
import com.deepseekharness.app.runtime.WebProcessManager;
import java.io.IOException;
import org.junit.Test;

/** 已认领实例没有伪造的Java Process；原启动器退出仍是独立停止屏障。 */
public final class RecoveredWebStopBarrierTest {
  private static final class Manager extends WebProcessManager {
    boolean closed, denied, tracked, scanClear;

    Manager() {
      super(null);
    }

    @Override
    public boolean recoveredLauncherExited(com.deepseekharness.app.util.WebSessionProof.Saved proof)
        throws IOException {
      if (denied) throw new IOException("PROC_DENIED");
      return closed;
    }

    @Override
    public boolean confirmStopped(boolean alive) {
      tracked = alive;
      return !alive;
    }

    @Override
    public boolean hasRecordedSession() {
      return false;
    }

    @Override
    public boolean confirmNoUntrackedWebLaunchers() throws IOException {
      if (denied) throw new IOException("PROC_DENIED");
      return scanClear;
    }
  }

  @Test
  public void liveOrUnverifiableLauncherKeepsBarrierAfterGuestStop() throws Exception {
    var state = new HarnessSessionState();
    var run = new WebLifecycleController.WebRun(7);
    run.recovered = new StoredWebSession.Record(null, "", "", "", "", false, "");
    state.currentRun = run;
    state.runs.put(7L, run);
    Manager manager = new Manager();
    var sessions =
        new WebProcessSession(
            state,
            manager,
            () -> {
              throw new AssertionError();
            });
    assertTrue(sessions.hasLiveLaunchers());
    assertFalse(sessions.confirmStopped());
    assertTrue(manager.tracked);
    assertFalse(sessions.stopOwnedLauncherFacts().isEmpty());
    manager.denied = true;
    assertTrue(sessions.hasLiveLaunchers());
    assertFalse(sessions.confirmStopped());
    assertFalse(sessions.stopOwnedLauncherFacts().isEmpty());
    manager.denied = false;
    manager.closed = true;
    assertFalse(sessions.hasLiveLaunchers());
    assertTrue(sessions.confirmStopped());
    assertTrue(sessions.stopOwnedLauncherFacts().isEmpty());
    assertNull(run.launcher);
    state.io.shutdownNow();
  }

  @Test
  public void incompatibleOrHttpRejectedRecordStillHoldsLauncherBarrierWithoutAdoption()
      throws Exception {
    var state = new HarnessSessionState();
    state.retainedWebStopProof =
        new com.deepseekharness.app.util.WebSessionProof.Saved(
            "boot", 7, "instance", "nonce", 42, 10, "web", 41, 9, "launcher");
    // APK不兼容或HTTP失败时，不会建立currentRun，更不会伪造Process句柄。
    assertNull(state.currentRun);
    assertTrue(state.runs.isEmpty());
    Manager manager = new Manager();
    var sessions =
        new WebProcessSession(
            state,
            manager,
            () -> {
              throw new AssertionError();
            });
    assertTrue(sessions.hasLiveLaunchers());
    assertFalse(sessions.confirmStopped());
    assertFalse(sessions.stopOwnedLauncherFacts().isEmpty());
    manager.closed = true;
    assertFalse(sessions.hasLiveLaunchers());
    assertTrue(sessions.confirmStopped());
    state.io.shutdownNow();
  }

  @Test
  public void unreadableOrLegacyProofRequiresRealLauncherAbsenceScan() throws Exception {
    var state = new HarnessSessionState();
    state.unreadableWebStopProof = true;
    Manager manager = new Manager();
    var sessions =
        new WebProcessSession(
            state,
            manager,
            () -> {
              throw new AssertionError();
            });
    assertFalse(sessions.confirmStopped());
    manager.denied = true;
    manager.scanClear = true;
    assertFalse(sessions.confirmStopped());
    manager.denied = false;
    assertTrue(sessions.confirmStopped());
    state.io.shutdownNow();
  }

  @Test
  public void stopBeforeRecoveryReadAndDirectRetirementInitializeProductionProofLoader()
      throws Exception {
    for (int path = 0; path < 3; path++) {
      var state = new HarnessSessionState();
      state.lifecycle.beginStop(); // 模拟通知Stop先于恢复worker到达。
      Manager manager = new Manager();
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      var proof =
          new com.deepseekharness.app.util.WebSessionProof.Saved(
              "boot", 7, "instance", "nonce", 42, 10, "web", 41, 9, "launcher");
      var sessions =
          new WebProcessSession(
              state,
              manager,
              () -> {
                throw new AssertionError();
              },
              () -> {
                reads.incrementAndGet();
                return new StoredWebSession.Record(proof, "", "", "", "", false, "");
              });
      if (path == 0) sessions.loadStopEvidence();
      else if (path == 1) assertFalse(sessions.confirmStopped());
      else assertFalse(sessions.stopOwnedLauncherFacts().isEmpty());
      assertSame(proof, state.retainedWebStopProof);
      assertFalse(sessions.confirmStopped());
      assertEquals(1, reads.get());
      manager.closed = true;
      assertTrue(sessions.confirmStopped());
      state.io.shutdownNow();
    }
  }

  @Test
  public void runtimeFailureInProofLoaderRemainsPendingOnRepeatedStop() throws Exception {
    var state = new HarnessSessionState();
    Manager manager = new Manager();
    var sessions =
        new WebProcessSession(
            state,
            manager,
            () -> {
              throw new AssertionError();
            },
            () -> {
              throw new SecurityException("PRIVATE_DIRECTORY_DENIED");
            });
    assertFalse(sessions.confirmStopped());
    assertTrue(state.webStopProofLoaded);
    assertTrue(state.unreadableWebStopProof);
    assertFalse(sessions.confirmStopped());
    state.io.shutdownNow();
  }
}
