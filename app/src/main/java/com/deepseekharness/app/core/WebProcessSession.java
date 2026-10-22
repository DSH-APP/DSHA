package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.core.WebLifecycleController.WebRun;
import com.deepseekharness.app.runtime.WebProcessManager;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;

/** Owns exact launcher handles and bridge leases; unknown guest exit still requires PID proof. */
final class WebProcessSession {
  interface BridgeLease extends AutoCloseable {
    void ensureStarted();

    @Override
    void close();
  }

  interface Bridges {
    BridgeLease acquire();
  }

  interface SessionRecords {
    com.deepseekharness.app.runtime.StoredWebSession.Record read() throws IOException;
  }

  private final HarnessSessionState state;
  private final WebProcessManager manager;
  private final Bridges bridges;
  private Runnable stopEvidence = () -> {};

  WebProcessSession(HarnessSessionState state, WebProcessManager manager, Bridges bridges) {
    this.state = state;
    this.manager = manager;
    this.bridges = bridges;
  }

  WebProcessSession(
      HarnessSessionState state,
      WebProcessManager manager,
      Bridges bridges,
      SessionRecords records) {
    this(state, manager, bridges);
    installStopEvidence(records);
  }

  WebProcessSession(Context context, HarnessSessionState state, WebProcessManager manager) {
    this(
        state,
        manager,
        () -> {
          HttpShellService.Lease lease = HttpShellService.acquire(context);
          return new BridgeLease() {
            public void ensureStarted() {
              lease.ensureStarted();
            }

            public void close() {
              lease.close();
            }
          };
        });
    installStopEvidence(() -> new com.deepseekharness.app.runtime.StoredWebSession(context).read());
  }

  private void installStopEvidence(SessionRecords records) {
    stopEvidence =
        () -> {
          synchronized (state.webStopProofLock) {
            if (state.webStopProofLoaded) return;
            try {
              var record = records.read();
              if (record != null) state.retainedWebStopProof = record.proof();
              else if (manager.hasRecordedSession()) state.unreadableWebStopProof = true;
            } catch (Exception unknown) {
              state.unreadableWebStopProof = true;
            } finally {
              state.webStopProofLoaded = true;
            }
          }
        };
  }

  boolean running() {
    return manager.isRunning();
  }

  void loadStopEvidence() {
    stopEvidence.run();
  }

  boolean confirmStopped() throws IOException {
    return manager.confirmStopped(hasLiveLaunchers());
  }

  boolean hasLiveLaunchers() {
    boolean alive = retainedLauncherPending();
    for (WebRun run : state.runs.values()) {
      if (run.recovered != null) {
        try {
          if (!manager.recoveredLauncherExited(run.recovered.proof())) alive = true;
        } catch (IOException unknown) {
          alive = true;
        }
      }
      Process process = run.launcher;
      if (process == null) prune(run);
      else if (Compat.isAlive(process)) alive = true;
      else {
        if (run.launcher == process) run.launcher = null;
        prune(run);
      }
    }
    return alive;
  }

  java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> stopOwnedLauncherFacts() {
    loadStopEvidence();
    boolean pending = false;
    for (WebRun run : state.runs.values()) {
      Process process = run.launcher;
      if (process == null || !Compat.isAlive(process)) {
        if (run.launcher == process) run.launcher = null;
        prune(run);
      } else if (Compat.requestGracefulStop(process, 4_000)) {
        if (run.launcher == process) run.launcher = null;
        prune(run);
      } else pending = true;
    }
    pending |= hasLiveLaunchers();
    return pending
        ? java.util.List.of(
            com.deepseekharness.app.util.WebStopDiagnostic.of(
                com.deepseekharness.app.util.WebStopDiagnostic.Code.OWNED_LAUNCHER_PENDING))
        : java.util.List.of();
  }

  String stopOwnedLaunchers() {
    return WebStopText.renderAll(stopOwnedLauncherFacts());
  }

  private boolean retainedLauncherPending() {
    // 所有停止、维护和新启旧实例退休入口共用，不能被先到达的Stop跳过恢复读取。
    stopEvidence.run();
    try {
      if (state.retainedWebStopProof != null
          && !manager.recoveredLauncherExited(state.retainedWebStopProof)) return true;
      return state.unreadableWebStopProof && !manager.confirmNoUntrackedWebLaunchers();
    } catch (IOException unknown) {
      return true;
    }
  }

  void acquireBridge(WebRun run) {
    BridgeLease lease = run.bridge;
    BridgeLease candidate = lease == null ? bridges.acquire() : null;
    synchronized (state.lifecycle) {
      if (state.lifecycle.isCurrent(run.generation)) {
        synchronized (run) {
          if (run.bridge == null) {
            run.bridge = candidate;
            candidate = null;
          }
          lease = run.bridge;
        }
      } else lease = null;
    }
    if (candidate != null) candidate.close();
    if (lease != null) lease.ensureStarted();
  }

  void releaseBridge(long generation) {
    WebRun run = state.runs.get(generation);
    if (run == null) return;
    BridgeLease lease;
    synchronized (run) {
      lease = run.bridge;
      run.bridge = null;
    }
    if (lease != null) lease.close();
    prune(run);
  }

  void releasePrior(long generation) {
    for (long old : state.runs.keySet()) if (old != generation) releaseBridge(old);
  }

  private void prune(WebRun run) {
    if (run != state.currentRun
        && run.bridge == null
        && run.recovered == null
        && (run.launcher == null || !Compat.isAlive(run.launcher)))
      state.runs.remove(run.generation, run);
  }
}
