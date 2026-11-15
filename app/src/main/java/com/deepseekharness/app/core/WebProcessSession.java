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
              if (record != null) {
                // 重读成功：以可核验的原记录代替先前“不可读”的保守结论，屏障仍按其原启动器判定。
                state.retainedWebStopProof = record.proof();
                state.unreadableWebStopProof = false;
              } else if (manager.hasRecordedSession()) state.unreadableWebStopProof = true;
              state.webStopProofLoaded = true;
            } catch (Exception unknown) {
              // 读取失败只保留“不可读”屏障，不锁存：覆盖安装后私有目录或密钥可能暂时不可用，
              // 若整进程只读一次，之后每次停止 / 启动都会卡在 retire-prior-launchers，直到进程被杀。
              state.unreadableWebStopProof = true;
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
    // Start/Stop advances the lifecycle generation before proving the previous runtime exited.
    // Capture only those prior objects: a later run or a replaced proof cannot inherit this result.
    java.util.ArrayList<StoppedRun> candidates = new java.util.ArrayList<>();
    long through;
    synchronized (state.lifecycle) {
      through = state.lifecycle.generation() - 1;
      for (WebRun run : state.runs.values())
        if (run.generation <= through && run.recovered != null)
          candidates.add(new StoppedRun(run, run.recovered, run.launcher));
    }
    if (!manager.confirmStopped(hasLiveLaunchers())) return false;
    synchronized (state.lifecycle) {
      for (StoppedRun candidate : candidates) {
        WebRun run = candidate.run();
        if (run.generation > through
            || state.runs.get(run.generation) != run
            || run.recovered != candidate.recovered()
            || run.launcher != null && run.launcher != candidate.launcher()) continue;
        // Full guest + launcher exit is now proven. Keeping this old PID proof would allow its
        // future reuse or permission changes to turn an already completed Stop back into UNKNOWN.
        run.recovered = null;
        run.recoveredLive = false;
        run.confirmedWebExit = true;
        prune(run);
      }
    }
    return true;
  }

  private record StoppedRun(
      WebRun run,
      com.deepseekharness.app.runtime.StoredWebSession.Record recovered,
      Process launcher) {}

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
    Integer pendingPid = null;
    for (WebRun run : state.runs.values()) {
      Process process = run.launcher;
      if (process == null || !Compat.isAlive(process)) {
        if (run.launcher == process) run.launcher = null;
        prune(run);
      } else if (Compat.requestGracefulStop(process, 4_000)) {
        if (run.launcher == process) run.launcher = null;
        prune(run);
      } else pendingPid = launcherPid(process);
    }
    boolean pending = pendingPid != null;
    pending |= hasLiveLaunchers();
    if (!pending) return java.util.List.of();
    // 启动前置停止的失败文案需要“已核验残留 PID + 命令摘要”；这里只读取，不发信号。
    java.util.List<Object> facts = new java.util.ArrayList<>();
    if (pendingPid != null) {
      facts.add("PID=" + pendingPid);
      String digest = manager.processCommandDigest(pendingPid);
      if (!digest.isEmpty()) facts.add("命令摘要=" + digest);
      facts.add("旧容器启动器");
    }
    return java.util.List.of(
        com.deepseekharness.app.util.WebStopDiagnostic.of(
            com.deepseekharness.app.util.WebStopDiagnostic.Code.OWNED_LAUNCHER_PENDING,
            facts.toArray()));
  }

  /** PID 只用于展示，读不到也不影响待确认结论（Android 的 Process 没有公开 pid 访问器）。 */
  private static Integer launcherPid(Process process) {
    try {
      int pid =
          com.deepseekharness.app.util.ProcessIdentity.androidPid(
              process.getClass().getName(), process.toString());
      return pid > 1 ? pid : null;
    } catch (RuntimeException unavailable) {
      return null;
    }
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
