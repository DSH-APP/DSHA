package com.deepseekharness.app.core;

import com.deepseekharness.app.util.Compat;

import android.content.Context;
import android.util.Log;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.WebProcessManager;
import com.deepseekharness.app.util.DshAuthUrl;
import com.deepseekharness.app.util.WebLifecycle;
import com.deepseekharness.app.util.WebProcSel;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One Web lifetime; all authoritative state remains in the application session. */
final class WebLifecycleController {

  private final Context ctx;
  private final ConfigStore config;
  private final ProotBootstrap proot;
  private final WebProcessManager webProc;

  private final HarnessSessionState state;
  private final WebLifecycle lifecycle;
  private final ScheduledExecutorService io;
  private final java.util.concurrent.ConcurrentHashMap<Long, WebRun> webRuns;
  private final com.deepseekharness.app.runtime.StoredWebSession storedSession;

  static final class WebRun {
    final long generation;
    final String instanceId;
    volatile Process launcher;
    volatile WebProcessSession.BridgeLease bridge;
    volatile String authUrl = "";
    volatile int port;
    volatile boolean compatible;
    volatile com.deepseekharness.app.util.AgentTaskRegistration registration;
    volatile com.deepseekharness.app.runtime.StoredWebSession.Record recovered;
    volatile boolean recoveredLive;
    volatile boolean confirmedWebExit;
    volatile boolean authorityRevocationPending;

    WebRun(long generation) {
      this(generation, java.util.UUID.randomUUID().toString());
    }

    WebRun(long generation, String instanceId) {
      this.generation = generation;
      this.instanceId = instanceId;
    }
  }

  /** An old stdout callback cannot publish credentials or a port into a newer run. */
  static boolean publishAuthenticatedRun(WebLifecycle gate, WebRun run, String url) {
    synchronized (gate) {
      if (run == null || !gate.isCurrent(run.generation)) return false;
      run.port = java.net.URI.create(url).getPort();
      run.authUrl = url;
      return true;
    }
  }

  private final HarnessController owner;
  private final WebProcessSession processes;
  private final LanAuthBridge lanAuth;

  interface ScreenLifetime {
    void revoke();

    void stop();
  }

  private final ScreenLifetime screenLifetime;

  WebLifecycleController(HarnessController owner, HarnessSessionState state) {
    this(
        owner,
        state,
        new ScreenLifetime() {
          public void revoke() {
            com.deepseekharness.app.HttpShellService.revokeScreenGrant(owner.context());
          }

          public void stop() {
            com.deepseekharness.app.vscreen.VirtualScreenManager.from(owner.context()).stop();
          }
        });
  }

  WebLifecycleController(
      HarnessController owner, HarnessSessionState state, ScreenLifetime screenLifetime) {
    this.owner = owner;
    this.screenLifetime = java.util.Objects.requireNonNull(screenLifetime);
    this.ctx = owner.context();
    this.config = owner.config();
    this.proot = owner.proot();
    this.webProc = new WebProcessManager(proot);
    this.state = state;
    this.lifecycle = state.lifecycle;
    this.io = state.io;
    this.webRuns = state.runs;
    this.storedSession = new com.deepseekharness.app.runtime.StoredWebSession(ctx);
    this.processes = new WebProcessSession(ctx, state, webProc);
    this.lanAuth = new LanAuthBridge(ctx, config, proot, state);
    synchronized (lifecycle) {
      if (state.authFailure == null)
        state.authFailure = com.deepseekharness.app.util.UiText.text("Web 鉴权尚未就绪，请稍后重试");
      if (state.recovery == null) state.recovery = new WebRecovery(config);
      if (state.diagnostics == null) {
        state.diagnostics = new StartupDiagnostics(ctx);
        state.diagnostics.onHealthy = generation -> StartupRepairs.healthy(ctx, owner, generation);
      }
    }
  }

  public boolean isRestoringWebSession() {
    return state.restoreChecking;
  }

  public boolean needsWebSessionRestore() {
    return lifecycle.generation() == 0 && !state.restoreChecked;
  }

  public boolean hasUnresolvedWebSession() {
    WebRun run = state.currentRun;
    return state.restoreUnknown
        && !lifecycle.isUserStopped()
        && !lifecycle.isStopping()
        && (lifecycle.generation() == 0
            || run != null
                && lifecycle.isCurrent(run.generation)
                && run.recovered != null
                && !run.recoveredLive
                && !run.confirmedWebExit);
  }

  public void restoreRunningWebSession(Runnable completed) {
    synchronized (lifecycle) {
      if (lifecycle.generation() != 0 || state.restoreChecked) {
        if (completed != null)
          new android.os.Handler(android.os.Looper.getMainLooper()).post(completed);
        return;
      }
      if (completed != null) state.restoreListeners.add(completed);
      if (state.restoreChecking) return;
      state.restoreChecking = true;
    }
    io.execute(
        () -> {
          var verifiedAuth = new java.util.concurrent.atomic.AtomicReference<RecoveredAuth>();
          var result =
              WebSessionRecovery.recover(
                  new WebSessionRecovery.Ports() {
                    public com.deepseekharness.app.runtime.StoredWebSession.Record read()
                        throws Exception {
                      try {
                        var record = storedSession.read();
                        if (record != null) state.retainedWebStopProof = record.proof();
                        if (record == null && webProc.hasRecordedSession())
                          throw new IOException("WEB_SESSION_RECORD_MISSING_FOR_EXISTING_PID");
                        return record;
                      } catch (Exception unknown) {
                        state.unreadableWebStopProof = true;
                        throw unknown;
                      }
                    }

                    public boolean current() {
                      return lifecycle.generation() == 0 && !lifecycle.isUserStopped();
                    }

                    public boolean compatible(
                        com.deepseekharness.app.runtime.StoredWebSession.Record r)
                        throws Exception {
                      var installed = proot.installedRuntimeDescriptor();
                      return r.app().equals(storedSession.appIdentity())
                          && r.root().equals(proot.getRootfsDir().getCanonicalPath())
                          && r.descriptor().equals(proot.expectedRuntimeDescriptor().id())
                          && installed != null
                          && r.descriptor().equals(installed.id())
                          && !hasStopSentinel()
                          && !MaintenanceCoordinator.pending(owner);
                    }

                    public boolean verify(com.deepseekharness.app.runtime.StoredWebSession.Record r)
                        throws Exception {
                      return webProc.verifySession(r.proof());
                    }

                    public boolean authenticate(
                        com.deepseekharness.app.runtime.StoredWebSession.Record r) {
                      var authentication =
                          com.deepseekharness.app.util.DshAuthSession.exchange(
                              r.authUrl(),
                              java.net.URI.create(r.authUrl()).getPort(),
                              this::current,
                              5000);
                      if (authentication.ready())
                        verifiedAuth.set(new RecoveredAuth(r, authentication));
                      return authentication.ready();
                    }

                    public boolean adopt(
                        com.deepseekharness.app.runtime.StoredWebSession.Record r) {
                      synchronized (lifecycle) {
                        if (!current() || !lifecycle.adoptRunning(r.proof().generation()))
                          return false;
                        WebRun run = new WebRun(r.proof().generation(), r.proof().instance());
                        run.recovered = r;
                        run.recoveredLive = true;
                        run.authUrl = r.authUrl();
                        run.port = java.net.URI.create(r.authUrl()).getPort();
                        run.compatible = r.compatible();
                        run.registration =
                            com.deepseekharness.app.util.AgentTaskRegistration.restore(
                                run.generation, run.instanceId, r.taskToken());
                        state.currentRun = run;
                        webRuns.put(run.generation, run);
                        state.recovery.begin(run.generation, false);
                        state.diagnostics.begin(run.generation, false);
                        state.diagnostics.stage(
                            run.generation,
                            com.deepseekharness.app.util.UiText.choose(
                                "已重新接入原 Web 实例", "Reconnected to the existing Web instance"));
                        return true;
                      }
                    }
                  });
          state.restoreUnknown = result == WebSessionRecovery.Result.UNKNOWN;
          state.restoreChecked = true;
          state.restoreChecking = false;
          DiagnosticLog.record(ctx, "WEB_SESSION_RESTORE", result.name());
          if (result == WebSessionRecovery.Result.RESTORED) {
            WebRun run = state.currentRun;
            try {
              if (run != null) processes.acquireBridge(run);
            } catch (RuntimeException unavailable) {
              DiagnosticLog.record(
                  ctx, "WEB_SESSION_BRIDGE", unavailable.getClass().getSimpleName());
            }
            notifyReadyWebPageChanged();
            try {
              publishRecoveredLan(result, state, verifiedAuth.get(), lanAuth);
            } catch (RuntimeException unavailable) {
              DiagnosticLog.record(
                  ctx, "WEB_SESSION_LAN_AUTH", unavailable.getClass().getSimpleName());
            }
          }
          var main = new android.os.Handler(android.os.Looper.getMainLooper());
          for (Runnable listener : state.restoreListeners) main.post(listener);
          state.restoreListeners.clear();
        });
  }

  record RecoveredAuth(
      com.deepseekharness.app.runtime.StoredWebSession.Record record,
      com.deepseekharness.app.util.DshAuthSession.Result authentication) {}

  /** Strict recovery already checked HTTP; publish only after the second kernel proof and adopt. */
  static String publishRecoveredLan(
      WebSessionRecovery.Result result,
      HarnessSessionState state,
      RecoveredAuth verified,
      LanAuthBridge bridge) {
    if (result != WebSessionRecovery.Result.RESTORED
        || verified == null
        || verified.record() == null
        || verified.record().proof() == null
        || verified.authentication() == null
        || !verified.authentication().ready()) return null;
    WebRun run;
    String url;
    int port;
    synchronized (state.lifecycle) {
      run = state.currentRun;
      if (run == null
          || state.restoreUnknown
          || run.recovered != verified.record()
          || !run.recoveredLive
          || !state.lifecycle.isCurrent(run.generation)
          || state.lifecycle.isStarting()
          || state.lifecycle.isStopping()
          || state.lifecycle.isUserStopped()
          || run.generation != verified.record().proof().generation()) return null;
      url = verified.record().authUrl();
      DshAuthUrl.Parsed parsed = DshAuthUrl.parse(url);
      if (parsed == null) return null;
      port = java.net.URI.create(url).getPort();
      if (!parsed.loopbackBaseUrl.equals("http://127.0.0.1:" + port + "/")
          || !url.equals(run.authUrl)
          || port != run.port) return null;
    }
    // The bridge rechecks the exact URL/port/generation and the current LAN
    // switch at bind. No second HTTP request, migration, or start is replayed.
    return bridge.publishVerified(run.generation, url, port, verified.authentication());
  }

  /** 前后台共用合并的异步复核；不在主线程读取proc或等待HTTP。 */
  public void refreshRecoveredWebSession() {
    requestWebSessionRecheck(null);
  }

  public void requestWebSessionRecheck(Runnable completed) {
    synchronized (lifecycle) {
      if (lifecycle.generation() == 0 && state.restoreUnknown && !state.restoreChecking) {
        state.restoreChecked = false;
        restoreRunningWebSession(completed);
        return;
      }
      if (completed != null) state.recheckListeners.add(completed);
      if (state.recheckPending) return;
      state.recheckPending = true;
    }
    io.execute(
        () -> {
          try {
            checkRecoveredWebSession();
          } finally {
            java.util.List<Runnable> listeners;
            synchronized (lifecycle) {
              state.recheckPending = false;
              listeners = java.util.List.copyOf(state.recheckListeners);
              state.recheckListeners.clear();
            }
            var main = new android.os.Handler(android.os.Looper.getMainLooper());
            for (Runnable listener : listeners) main.post(listener);
          }
        });
  }

  private void checkRecoveredWebSession() {
    WebRun run = state.currentRun;
    if (run == null || run.recovered == null) return;
    var record = run.recovered;
    if (!finishAuthorityRevocation(
        state,
        run,
        record,
        () -> {
          screenLifetime.revoke();
          com.deepseekharness.app.AgentTaskNotifications.invalidateRuntime(
              run.generation, run.instanceId);
        })) return;
    var outcome =
        WebSessionRecheck.check(
            new WebSessionRecheck.Ports() {
              public boolean current() {
                return currentRecheck(state, run, record);
              }

              public boolean alreadyLive() {
                return run.recoveredLive && !state.restoreUnknown;
              }

              public WebProcessManager.SessionObservation observe() {
                return webProc.observeSession(record.proof());
              }

              public com.deepseekharness.app.util.DshAuthSession.Result authenticate() {
                return com.deepseekharness.app.util.DshAuthSession.exchange(
                    record.authUrl(), run.port, this::current, 5000);
              }
            });
    if (!applyRecheckedWebSession(state, run, record, outcome)) return;
    finishAuthorityRevocation(
        state,
        run,
        record,
        () -> {
          screenLifetime.revoke();
          com.deepseekharness.app.AgentTaskNotifications.invalidateRuntime(
              run.generation, run.instanceId);
        });
    if (outcome.result() == WebSessionRecheck.Result.RESTORED) {
      publishRecoveredLan(
          WebSessionRecovery.Result.RESTORED,
          state,
          new RecoveredAuth(record, outcome.authentication()),
          lanAuth);
      DiagnosticLog.record(ctx, "WEB_SESSION_RECHECK", "RESTORED");
    } else if (outcome.result() == WebSessionRecheck.Result.EXITED)
      state.diagnostics.message(run.generation, "WEB_GUEST_EXIT_CONFIRMED");
    notifyReadyWebPageChanged();
  }

  static boolean currentRecheck(
      HarnessSessionState state,
      WebRun run,
      com.deepseekharness.app.runtime.StoredWebSession.Record record) {
    synchronized (state.lifecycle) {
      return run != null
          && state.currentRun == run
          && run.recovered == record
          && record != null
          && state.lifecycle.isCurrent(run.generation)
          && !state.lifecycle.isStarting()
          && !state.lifecycle.isStopping()
          && !state.lifecycle.isUserStopped()
          && record.proof().generation() == run.generation
          && record.proof().instance().equals(run.instanceId)
          && record.authUrl().equals(run.authUrl)
          && java.net.URI.create(record.authUrl()).getPort() == run.port;
    }
  }

  static boolean applyRecheckedWebSession(
      HarnessSessionState state,
      WebRun run,
      com.deepseekharness.app.runtime.StoredWebSession.Record record,
      WebSessionRecheck.Outcome outcome) {
    synchronized (state.lifecycle) {
      if (!currentRecheck(state, run, record) || outcome.result() == WebSessionRecheck.Result.STALE)
        return false;
      if (outcome.result() == WebSessionRecheck.Result.LIVE) return false;
      if (outcome.result() == WebSessionRecheck.Result.RESTORED) {
        if (run.authorityRevocationPending
            || outcome.authentication() == null
            || !outcome.authentication().ready()) return false;
        if (run.registration != null) run.registration.revoke();
        run.registration =
            com.deepseekharness.app.util.AgentTaskRegistration.restore(
                run.generation, run.instanceId, record.taskToken());
        run.recoveredLive = true;
        run.confirmedWebExit = false;
        state.restoreUnknown = false;
        return true;
      }
      boolean changed =
          run.recoveredLive
              || !state.restoreUnknown
              || run.confirmedWebExit != (outcome.result() == WebSessionRecheck.Result.EXITED);
      applyRecoveredWebProof(state, run, false);
      run.confirmedWebExit = outcome.result() == WebSessionRecheck.Result.EXITED;
      if (changed) run.authorityRevocationPending = true;
      return changed;
    }
  }

  /** 只由同一IO队列调用；真实新启发布排在本次撤销之后，界面启停只能先改变意图。 */
  static boolean finishAuthorityRevocation(
      HarnessSessionState state,
      WebRun run,
      com.deepseekharness.app.runtime.StoredWebSession.Record record,
      Runnable revoke) {
    synchronized (state.lifecycle) {
      if (!currentRecheck(state, run, record)) return false;
      if (!run.authorityRevocationPending) return true;
    }
    // HTTP的screen检查会从其自身锁调用controller，不能在lifecycle内反向获取该锁。
    try {
      revoke.run();
    } catch (RuntimeException unavailable) {
      return false;
    }
    synchronized (state.lifecycle) {
      if (!currentRecheck(state, run, record)) return false;
      run.authorityRevocationPending = false;
      return true;
    }
  }

  /** 展示身份只用于保留原页面，不能据此批准DOM/文件/麦克风或任务请求。 */
  public boolean isWebSessionRetained(
      com.deepseekharness.app.util.PreviewPageSession.Identity expected) {
    WebRun run = state.currentRun;
    return expected != null
        && run != null
        && run.recovered != null
        && currentRecheck(state, run, run.recovered)
        && !run.recoveredLive
        && expected.generation() == run.generation
        && expected.instanceId().equals(run.instanceId)
        && expected.authUrl().equals(run.authUrl);
  }

  public boolean isWebSessionExited(
      com.deepseekharness.app.util.PreviewPageSession.Identity expected) {
    WebRun run = state.currentRun;
    return isWebSessionRetained(expected) && run != null && run.confirmedWebExit;
  }

  /** Apply only this run's observed proof; UNKNOWN retires LAN, never the Web process. */
  static boolean applyRecoveredWebProof(HarnessSessionState state, WebRun run, boolean live) {
    synchronized (state.lifecycle) {
      if (!state.lifecycle.isCurrent(run.generation) || state.currentRun != run) return false;
      run.recoveredLive = live;
      if (!live) state.restoreUnknown = true;
      if (!live && run.registration != null) run.registration.revoke();
      // Existing bridge publication uses the same lifecycle -> proxy lock order.
      // clearDshAuth only retires this generation's LAN credential and sockets.
      if (!live) com.deepseekharness.app.LanProxyService.clearDshAuth(run.generation);
      return true;
    }
  }

  private void saveRunningSession(WebRun run) {
    if (!lifecycle.isCurrent(run.generation) || run.registration == null) return;
    try {
      String token = run.registration.launchEnvironment().get("DSH_TASK_TOKEN");
      var proof = webProc.captureSession(run.generation, run.instanceId, token, run.launcher);
      var record =
          new com.deepseekharness.app.runtime.StoredWebSession.Record(
              proof,
              run.authUrl,
              storedSession.appIdentity(),
              proot.getRootfsDir().getCanonicalPath(),
              proot.expectedRuntimeDescriptor().id(),
              run.compatible,
              token);
      // 保存与停止完成后的清退共用IO队列，后来的停止意图优先。
      if (lifecycle.isCurrent(run.generation)) {
        storedSession.write(record);
        state.retainedWebStopProof = proof;
        run.recovered = record;
        run.recoveredLive = true;
      }
    } catch (Exception unavailable) {
      DiagnosticLog.record(ctx, "WEB_SESSION_SAVE", unavailable.getClass().getSimpleName());
    }
  }

  /** Web 是否在运行（按 pid 文件 + kill -0 判断，不依赖端口反查）。 */
  public boolean isWebRunning() {
    return runningWithProof(state, processes::running);
  }

  static boolean runningWithProof(
      HarnessSessionState state, java.util.function.BooleanSupplier probe) {
    WebRun run;
    long generation;
    synchronized (state.lifecycle) {
      run = state.currentRun;
      generation = state.lifecycle.generation();
      if (!runningProofAllowed(state, run)) return false;
    }
    boolean running = probe.getAsBoolean();
    synchronized (state.lifecycle) {
      return running
          && generation == state.lifecycle.generation()
          && state.currentRun == run
          && runningProofAllowed(state, run);
    }
  }

  private static boolean runningProofAllowed(HarnessSessionState state, WebRun run) {
    return !state.restoreUnknown
        && !state.lifecycle.isStopping()
        && !state.lifecycle.isUserStopped()
        && (run == null
            || !run.authorityRevocationPending && (run.recovered == null || run.recoveredLive));
  }

  public boolean isWebStoppedForMaintenance() throws IOException {
    return processes.confirmStopped();
  }

  /** 最近一次停止任务的结构化结果；仅供同一维护屏障读取，不含用户内容。 */
  public String lastStopError() {
    return state.lastStopError.isEmpty() ? "" : state.lastStopResult.detail();
  }

  public WebStopCoordinator.Result lastStopResult() {
    return state.lastStopResult;
  }

  /** 维护前确认所有本进程启动的 Web 启动器及其管道已退出；不按名称误杀容器。 */
  public boolean hasLiveWebProcesses() {
    return processes.hasLiveLaunchers();
  }

  /** 当前 BrowserAuth 鉴权链接；dsh 还没打印出来时为空串。 */
  public int getWebPort() {
    WebRun run = state.currentRun;
    return run != null && run.port > 0 ? run.port : config.getPortInt();
  }

  public String getWebAuthUrl() {
    WebRun run = state.currentRun;
    return run == null
            || run.authorityRevocationPending
            || run.recovered != null && !run.recoveredLive
        ? ""
        : run.authUrl;
  }

  /** 地址可以复用，网页只能属于本次仍存活且已经鉴权的正式实例。 */
  public com.deepseekharness.app.util.PreviewPageSession.Identity getReadyWebPageIdentity() {
    synchronized (lifecycle) {
      WebRun run = state.currentRun;
      return readyWebPageIdentity(
          lifecycle, run, run != null && state.recovery.failed(run.generation));
    }
  }

  static com.deepseekharness.app.util.PreviewPageSession.Identity readyWebPageIdentity(
      WebLifecycle lifecycle, WebRun run, boolean failed) {
    synchronized (lifecycle) {
      if (run == null
          || failed
          || !lifecycle.isCurrent(run.generation)
          || lifecycle.isStarting()
          || lifecycle.isStopping()
          || lifecycle.isUserStopped()
          || run.authUrl.isEmpty()
          || run.authorityRevocationPending
          || (run.recovered != null && !run.recoveredLive)
          || (run.launcher == null || !Compat.isAlive(run.launcher))
              && !(run.recovered != null && run.recoveredLive)) return null;
      return new com.deepseekharness.app.util.PreviewPageSession.Identity(
          run.generation, run.instanceId, run.authUrl);
    }
  }

  public com.deepseekharness.app.util.AgentTaskRegistration.Snapshot getReadyTaskRegistration() {
    synchronized (lifecycle) {
      WebRun run = state.currentRun;
      if (run == null || run.registration == null) return null;
      return run.registration.ready(
          readyWebPageIdentity(lifecycle, run, state.recovery.failed(run.generation)));
    }
  }

  public void addReadyWebPageListener(Runnable listener) {
    if (listener == null) throw new IllegalArgumentException("PREVIEW_PAGE_LISTENER");
    state.readyWebPageListeners.add(listener);
  }

  public void removeReadyWebPageListener(Runnable listener) {
    state.readyWebPageListeners.remove(listener);
  }

  private void notifyReadyWebPageChanged() {
    if (state.readyWebPageListeners.isEmpty()) return;
    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    for (Runnable listener : state.readyWebPageListeners) {
      main.post(
          () -> {
            // 页面退到后台后的排队事件不能再次认领旧 Activity。
            if (!state.readyWebPageListeners.contains(listener)) return;
            try {
              listener.run();
            } catch (RuntimeException e) {
              Log.w("DSHA", "PREVIEW_PAGE_LISTENER_FAILED", e);
            }
          });
    }
  }

  /** dsh 实际启动命令（写 pid 文件要在 exec 之前，exec 不换 pid）。 */
  public String runCoreCommand() {
    return runCoreCommand("web", config.getRequestedWebPort(), getWebGeneration());
  }

  private String runCoreCommand(String profile, int listenPort, long generation) {
    return com.deepseekharness.app.util.WebLaunchCommand.build(
        config.getPermissionMode(),
        config.isConfirmShell(),
        config.getUiLanguage(),
        profile,
        listenPort,
        generation);
  }

  /**
   * 后台启动 dsh：先清残留进程（避免端口冲突），确保运行时与 rootfs 就绪后拉起 dsh web，
   * 独立线程捕获 BrowserAuth 鉴权链接。
   */
  public boolean startWeb(Consumer<String> onStatus) {
    return requestStart(onStatus, false, 0, false);
  }

  public boolean startWebSafely(Consumer<String> onStatus) {
    return requestStart(onStatus, false, 0, true);
  }

  public boolean startWebIfUnchanged(
      long expectedGeneration, boolean manual, Consumer<String> onStatus) {
    return requestStart(onStatus, !manual, expectedGeneration, false, expectedGeneration);
  }

  /** 看门狗不能撤销用户停止意图；检查与入队在同一把锁内完成。 */
  public boolean restartWebAutomatically(long expectedGeneration, Consumer<String> onStatus) {
    if (!canAutoRestart()) return false;
    synchronized (lifecycle) {
      if (expectedGeneration != lifecycle.generation() || !lifecycle.canAutoStart(false))
        return false;
      recordWebFailure(
          expectedGeneration, com.deepseekharness.app.util.UiText.text("服务连续三次健康检查未响应"));
      if (state.recovery.blocked()) return false;
    }
    return requestStart(onStatus, true, expectedGeneration, state.diagnostics.snapshot().safe);
  }

  private boolean requestStart(
      Consumer<String> onStatus, boolean automatic, long expectedGeneration, boolean safeMode) {
    return requestStart(onStatus, automatic, expectedGeneration, safeMode, lifecycle.generation());
  }

  private boolean requestStart(
      Consumer<String> onStatus,
      boolean automatic,
      long expectedGeneration,
      boolean safeMode,
      long admissionGeneration) {
    synchronized (lifecycle) {
      if (lifecycle.generation() != admissionGeneration) return false;
      if (state.restoreChecking) {
        if (onStatus != null)
          onStatus.accept(
              com.deepseekharness.app.util.UiText.choose(
                  "正在核对原 Web 实例，请稍后重试", "Checking the existing Web instance. Try again shortly."));
        return false;
      }
      if (automatic && state.restoreUnknown) return false;
      if (automatic
          && (lifecycle.isUserStopped() || lifecycle.isStarting() || lifecycle.isStopping()))
        return false;
    }
    // ADB reconnect owns this nonblocking gate too. Never wait on filesystem preflight or
    // hold the lifecycle monitor while that task is still preparing the environment.
    if (MaintenanceCoordinator.isEnvironmentTaskBusy()) {
      if (onStatus != null)
        onStatus.accept(com.deepseekharness.app.util.UiText.text("正在执行环境任务，完成后再启动 Web"));
      return false;
    }
    // 覆盖安装后，前台 START_STICKY 服务可能先于 MainActivity 被系统重建。
    // 尚未尝试的新受管候选必须先走维护事务；否则看门狗会用兼容但过期的
    // 运行时重新拉起 Web，令本 APK 的运行补丁和系统插件尚未落地。
    // 已经尝试失败的候选仍可使用通过健康确认的兼容前代。
    if (!proot.isEnvironmentReady() || EnvironmentAccess.requiresMaintenanceBeforeStart(owner)) {
      if (onStatus != null)
        onStatus.accept(
            com.deepseekharness.app.util.UiText.choose(
                "运行环境缺失或需要更新，请进入安装与修复；配置安全启动不能修复系统文件。",
                "The runtime is missing or needs an update. Open installation and repair; safe configuration cannot repair system files."));
      return false;
    }
    if (StartupRepairs.pending(ctx)) {
      if (onStatus != null)
        onStatus.accept(
            com.deepseekharness.app.util.UiText.choose(
                "配置修复尚未完成，请先进入启动恢复。",
                "Configuration repair is incomplete. Open Startup Recovery first."));
      return false;
    }
    if (com.deepseekharness.app.core.MaintenanceCoordinator.pending(owner)) {
      if (onStatus != null)
        onStatus.accept(com.deepseekharness.app.util.UiText.text("上次环境维护未完成，请先到安装与修复页恢复中断维护"));
      return false;
    }
    boolean stopSentinelPresent = hasStopSentinel();
    synchronized (lifecycle) {
      // Slow preflight does not reserve authority. A Stop/new intent during it wins.
      if (lifecycle.generation() != admissionGeneration) return false;
      if (com.deepseekharness.app.core.MaintenanceCoordinator.isEnvironmentTaskBusy()) {
        if (onStatus != null)
          onStatus.accept(com.deepseekharness.app.util.UiText.text("正在执行环境任务，完成后再启动 Web"));
        return false;
      }
      if (automatic
          && (state.recovery.blocked()
              || expectedGeneration != lifecycle.generation()
              || Thread.currentThread().isInterrupted())) return false;
      com.deepseekharness.app.util.EnvironmentTaskGate.Lease startup =
          com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(
              com.deepseekharness.app.util.UiText.text("启动 Web"));
      if (startup == null) {
        if (onStatus != null)
          onStatus.accept(com.deepseekharness.app.util.UiText.text("已有环境任务启动，请等待完成后重试"));
        return false;
      }
      long startedGeneration = -1;
      boolean transferred = false;
      try {
        final long generation = lifecycle.beginStart(automatic, stopSentinelPresent);
        if (generation < 0) return false;
        startedGeneration = generation;
        boolean priorCompatible = state.currentRun != null && state.currentRun.compatible;
        if (state.currentRun != null && state.currentRun.registration != null)
          state.currentRun.registration.revoke();
        WebRun run = new WebRun(generation);
        state.currentRun = run;
        state.restoreUnknown = false;
        webRuns.put(generation, run);
        notifyReadyWebPageChanged();
        state.recovery.begin(generation, !automatic);
        config.requestStartupRecovery(false);
        state.diagnostics.begin(generation, safeMode);
        state.diagnostics.message(
            generation,
            com.deepseekharness.app.util.UiText.format(
                "运行方式：%s",
                config.isProroot() && android.os.Build.VERSION.SDK_INT >= 26
                    ? "proroot"
                    : "proot"));
        new File(proot.getRootfsDir(), "root/.dsha-web-activity.json").delete();
        io.execute(
            () -> {
              try (startup) {
                startup.run(
                    () -> {
                      startWeb(
                          generation, onStatus, safeMode, !automatic, automatic && priorCompatible);
                      return null;
                    });
              } catch (Exception e) {
                lifecycle.finishStart(generation);
                recordWebFailure(generation, String.valueOf(e));
                reportStatus(
                    generation,
                    onStatus,
                    com.deepseekharness.app.util.UiText.format(
                        "启动未完成：%s",
                        com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e))));
              }
            });
        transferred = true;
        return true;
      } catch (RuntimeException e) {
        String detail =
            com.deepseekharness.app.util.UiText.format(
                "启动排队失败：%s", com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e)));
        if (startedGeneration >= 0) {
          lifecycle.finishStart(startedGeneration);
          recordWebFailure(startedGeneration, detail);
          reportStatus(startedGeneration, onStatus, detail);
        } else if (onStatus != null) onStatus.accept(detail);
        return false;
      } finally {
        // 入队前任何一步失败均释放；成功后由启动 worker 独占并负责关闭。
        if (!transferred) startup.close();
      }
    }
  }

  private void startWeb(
      long generation,
      Consumer<String> onStatus,
      boolean safeMode,
      boolean manual,
      boolean compatible) {
    WebRun run = webRuns.get(generation);
    if (run == null) return;
    ProotBootstrap boot = compatible ? new ProotBootstrap(ctx, true) : proot;
    boolean draining = false;
    try (com.deepseekharness.app.runtime.RuntimeHostPorts.Scope invocation =
        owner.runtimeHostPorts().open()) {
      if (!lifecycle.isCurrent(generation)
          || com.deepseekharness.app.core.MaintenanceCoordinator.isExclusive()) return;
      if (com.deepseekharness.app.core.MaintenanceCoordinator.pending(owner)) {
        reportStatus(
            generation, onStatus, com.deepseekharness.app.util.UiText.text("存在未完成的环境维护，请先恢复中断维护"));
        return;
      }
      processes.loadStopEvidence();
      String startupProfile =
          new StartupPipeline(config, boot, proot, webProc, state.diagnostics)
              .prepare(
                  generation,
                  safeMode,
                  new StartupEnvironmentActions(
                      owner,
                      state,
                      processes,
                      generation,
                      stage -> setWebStage(generation, stage),
                      status -> reportStatus(generation, onStatus, status)));
      if (startupProfile == null) return;
      // 准备流水线已分别确认旧guest与启动器全部退出；只有真实退休才可替换旧实例记录。
      storedSession.clear();
      String runtimeName = boot.runtime().id();
      state.diagnostics.message(
          generation, com.deepseekharness.app.util.UiText.format("本次实际运行方式：%s", runtimeName));
      run.compatible = compatible;
      int preferred = config.getRequestedWebPort();
      com.deepseekharness.app.util.WebPortPolicy.Choice ports =
          com.deepseekharness.app.util.WebPortPolicy.choose(
              preferred,
              config.fallbackWebPort(preferred),
              com.deepseekharness.app.util.WebPortPolicy::available);
      if (!lifecycle.isCurrent(generation)) return;
      run.port = ports.listen;
      if (preferred > 0 && ports.fallback()) {
        reportStatus(
            generation,
            onStatus,
            com.deepseekharness.app.util.UiText.format("首选 Web 端口已占用：%s", preferred));
        reportStatus(
            generation,
            onStatus,
            com.deepseekharness.app.util.UiText.text("正在自动选择可用 Web 端口，首选端口设置保留"));
      }
      Map<String, String> launchEnvironment =
          new java.util.HashMap<>(
              StartupPipeline.legacyCredentialEnvironment(
                  config.readApiKey(), warning -> reportStatus(generation, onStatus, warning)));
      synchronized (lifecycle) {
        if (!lifecycle.isCurrent(generation)) return;
        if (run.registration != null) run.registration.revoke();
        run.registration =
            new com.deepseekharness.app.util.AgentTaskRegistration(generation, run.instanceId);
        launchEnvironment.putAll(run.registration.launchEnvironment());
        launchEnvironment.put("DSHA_WEB_GENERATION", Long.toString(generation));
      }
      Process p =
          boot.execRootfs(
              runCoreCommand(startupProfile, ports.listen, generation), launchEnvironment);
      run.launcher = p;
      setWebStage(generation, com.deepseekharness.app.util.UiText.text("等待鉴权链接"));
      // 3090 桥就绪：agent 在容器里调设备能力（/exec /confirm /status）走这条通道。
      // 本代 Web 持有自己的需求；其它服务退出不能关闭仍在使用的桥。
      try {
        processes.acquireBridge(run);
      } catch (Throwable e) {
        Log.w(
            "DSHA",
            com.deepseekharness.app.util.UiText.format(
                "3090 桥启动失败：%s",
                com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e))));
      }
      if (ports.listen == 0)
        reportStatus(
            generation,
            onStatus,
            com.deepseekharness.app.util.UiText.text("Web 进程已创建，等待系统分配端口和鉴权链接"));
      else
        reportStatus(
            generation,
            onStatus,
            com.deepseekharness.app.util.UiText.format(
                "dsh web 进程已创建 → 127.0.0.1:%s（等待鉴权链接…）", ports.listen));
      Thread drainer =
          new Thread(
              () ->
                  drainWebOutput(p, generation, onStatus, runtimeName, compatible, safeMode, ports),
              "dsh-drain");
      drainer.setDaemon(true);
      drainer.start();
      // 启动慢只提示；保持启动状态，看门狗不会因此重启，用户仍可随时手动停止。
      io.schedule(() -> reportSlowStart(generation, onStatus), 60, TimeUnit.SECONDS);
      draining = true;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      String failure;
      if (e instanceof com.deepseekharness.app.util.CredentialRead.Unavailable) {
        var unreadable = ((com.deepseekharness.app.util.CredentialRead.Unavailable) e).result;
        Log.w("DSHA", "credential read: " + unreadable.reason.name());
        failure = ConfigStore.credentialMessage(unreadable);
      } else {
        Log.e("DSHA", "startWeb failed", e);
        failure = com.deepseekharness.app.util.UiText.format("启动失败：%s", e.getMessage());
      }
      lifecycle.finishStart(generation);
      recordWebFailure(generation, failure);
      reportStatus(generation, onStatus, failure);
    } finally {
      if (!draining) lifecycle.finishStart(generation);
    }
  }

  /** 读 dsh 进程输出：抓鉴权链接（宽松）、并把脱敏后的输出落到容器日志方便排查。 */
  private void drainWebOutput(
      Process p,
      long generation,
      Consumer<String> onStatus,
      String runtimeName,
      boolean compatible,
      boolean safeMode,
      com.deepseekharness.app.util.WebPortPolicy.Choice ports) {
    WebRun run = webRuns.get(generation);
    if (run == null) return;
    new WebOutputSession(ports.listen)
        .drain(
            p,
            new WebOutputSession.Events() {
              public boolean current() {
                return lifecycle.isCurrent(generation);
              }

              public void output(String lines) {
                synchronized (lifecycle) {
                  if (!current()) return;
                  appendHostLog(lines);
                  state.diagnostics.output(generation, lines);
                  if (state.diagnostics.hasExplicitStartupFailure(generation))
                    failedWebPage(generation, state.diagnostics.failureReason());
                }
              }

              public boolean authenticated() {
                synchronized (lifecycle) {
                  return !run.authUrl.isEmpty()
                      || state.recovery.failed(generation)
                      || state.diagnostics.hasExplicitStartupFailure(generation);
                }
              }

              public void authentication(String url) {
                synchronized (lifecycle) {
                  if (!current() || authenticated()) return;
                  if (!state.recovery.failed(generation)) {
                    if (!publishAuthenticatedRun(lifecycle, run, url)) return;
                    if (ports.preferred == 0 || ports.fallback()) {
                      config.rememberFallbackWebPort(ports.preferred, run.port);
                      reportStatus(
                          generation,
                          onStatus,
                          com.deepseekharness.app.util.UiText.format("实际 Web 端口：%s", run.port));
                    }
                    webProc.recordIdentity();
                    setWebStage(
                        generation, com.deepseekharness.app.util.UiText.text("服务已就绪，等待进入网页"));
                    lifecycle.finishStart(generation);
                    reportStatus(
                        generation,
                        onStatus,
                        com.deepseekharness.app.util.UiText.text("鉴权链接已就绪，点「进入对话」即可进入 dsh"));
                  }
                }
                notifyReadyWebPageChanged();
                io.execute(() -> saveRunningSession(run));
                // LAN exchanges and migration checks never pause the stdout drain.
                Thread ready =
                    new Thread(
                        () -> afterAuthenticated(generation, onStatus, safeMode), "dsha-web-ready");
                ready.setDaemon(true);
                ready.start();
              }

              public void readFailure(IOException error) {
                state.diagnostics.message(
                    generation, "WEB_OUTPUT_READ_FAILED:" + error.getClass().getSimpleName());
              }

              public void exited(int exitCode) {
                // proroot启动器可能先于已鉴权guest退休；父进程退出不能当作guest退出或业务重启依据。
                if (run.recovered != null
                    && lifecycle.isCurrent(generation)
                    && !run.authUrl.isEmpty()) {
                  synchronized (lifecycle) {
                    if (!lifecycle.isCurrent(generation) || state.currentRun != run) return;
                    if (run.launcher == p) run.launcher = null;
                  }
                  // 与前后台复核共用同一IO队列；父进程退休不授予guest退出结论。
                  requestWebSessionRecheck(null);
                  return;
                }
                if (run.registration != null) run.registration.revoke();
                if (run.launcher == p) run.launcher = null;
                processes.releaseBridge(generation);
                synchronized (lifecycle) {
                  if (!lifecycle.isCurrent(generation)) return;
                  boolean hadAuth = !run.authUrl.isEmpty();
                  String exitReason =
                      com.deepseekharness.app.util.UiText.format(
                          hadAuth ? "%s 进程退出，退出码 %s（鉴权后）" : "%s 进程退出，退出码 %s（鉴权前）",
                          runtimeName,
                          exitCode);
                  state.diagnostics.message(generation, exitReason);
                  state.diagnostics.preserveFailure(
                      new File(proot.getRootfsDir(), "root/dsh-web.log"), exitReason);
                  boolean namedFailure =
                      state.diagnostics.hasExplicitStartupFailure(generation)
                          || state.diagnostics.snapshot().issues.keySet().stream()
                              .anyMatch(name -> !name.isEmpty());
                  if (com.deepseekharness.app.util.WebRuntimeFallback.shouldRetry(
                      runtimeName,
                      compatible,
                      hadAuth,
                      namedFailure,
                      lifecycle.isCurrent(generation),
                      exitCode)) {
                    reportStatus(
                        generation,
                        onStatus,
                        com.deepseekharness.app.util.UiText.text(
                            "proroot 已退出，正在自动使用 proot 兼容重试；本轮只重试一次。"));
                    io.execute(() -> retryWithProot(generation, onStatus, safeMode));
                    return;
                  }
                  run.authUrl = "";
                  lifecycle.finishStart(generation);
                  com.deepseekharness.app.LanProxyService.stop(generation);
                  recordWebFailure(generation, exitReason);
                  reportStatus(
                      generation,
                      onStatus,
                      hadAuth
                          ? com.deepseekharness.app.util.UiText.text("dsh 进程已退出")
                          : com.deepseekharness.app.util.UiText.text(
                              "启动失败：进程在鉴权前退出。下方保留实际错误；可查看恢复选项或安全启动。"));
                }
              }
            });
  }

  private void afterAuthenticated(long generation, Consumer<String> onStatus, boolean safeMode) {
    String startupId = state.diagnostics.recordId();
    WebReadyTasks.run(
        safeMode,
        config.isLanMode(),
        new WebReadyTasks.Ports() {
          public boolean current() {
            return lifecycle.isCurrent(generation);
          }

          public void asynchronous(String name, Runnable work) {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            thread.start();
          }

          public void delay(long millis) throws InterruptedException {
            Thread.sleep(millis);
          }

          public String finalizeMigration() throws Exception {
            return com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
                proot.finalizeRc1MigrationResult(startupId), "RC1_FINALIZE");
          }

          public void migrationLog(String value) {
            state.diagnostics.message(generation, value);
          }

          public void migrationNeedsAttention() {
            reportStatus(
                generation,
                onStatus,
                com.deepseekharness.app.util.UiText.choose(
                    "网页已就绪；部分旧数据仍待处理，请在保留数据中检查迁移记录。",
                    "The web service is ready; some legacy data needs attention in Retained data."));
          }

          public boolean exchangeCookie() {
            return lanAuth.exchange(generation) != null;
          }

          public boolean lanBound() {
            return com.deepseekharness.app.LanProxyService.isBound();
          }

          public void lanReady() {
            reportStatus(
                generation,
                onStatus,
                com.deepseekharness.app.util.UiText.text("局域网代理已就绪：同网段设备可访问，启动页可复制地址"));
          }
        });
  }

  private void retryWithProot(long generation, Consumer<String> onStatus, boolean safeMode) {
    synchronized (lifecycle) {
      if (!lifecycle.isCurrent(generation) || !lifecycle.isStarting()) return;
    }
    com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
        com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(
            com.deepseekharness.app.util.UiText.text("proroot 退出后的兼容重试"));
    if (lease == null) {
      lifecycle.finishStart(generation);
      reportStatus(
          generation,
          onStatus,
          com.deepseekharness.app.util.UiText.text("环境任务正在进行，兼容重试未启动；完成后可选择 proot 再启动。"));
      return;
    }
    try (lease) {
      lease.run(
          () -> {
            startWeb(generation, onStatus, safeMode, false, true);
            return null;
          });
    } catch (Exception error) {
      lifecycle.finishStart(generation);
      reportStatus(
          generation,
          onStatus,
          com.deepseekharness.app.util.UiText.format("兼容重试失败：%s", error.getMessage()));
    }
  }

  public boolean isWebCompatibilityFallback() {
    WebRun run = state.currentRun;
    return run != null && run.compatible;
  }

  /** 把 dsh 输出脱敏后落到容器内 /root/dsh-web.log（排查用，鉴权 token 不落盘）。 */
  private void appendHostLog(String chunk) {
    try {
      File log = new File(proot.getRootfsDir(), "root/dsh-web.log");
      if (log.getParentFile() != null) log.getParentFile().mkdirs();
      Compat.append(
          log,
          com.deepseekharness.app.util.SensitiveData.redact(redactAuthUrl(chunk))
              .getBytes(StandardCharsets.UTF_8));
    } catch (Throwable ignored) {
    }
  }

  /** 把鉴权 token 打码，避免落盘泄露。 */
  static String redactAuthUrl(String s) {
    return DshAuthUrl.redact(s);
  }

  /**
   * Java 侧直接做一次 BrowserAuth cookie 交换：GET 鉴权链接，取回 dsh-auth-* cookie。
   * 返回 {@code "name=value"} 或 null。用于 WebView 的确定性注入鉴权。
   * 拿到 cookie 后若开了 LAN 模式，同步启动局域网反向代理（3081）。
   */
  public String getWebAuthFailure() {
    return com.deepseekharness.app.util.UiStateText.render(state.authFailure);
  }

  public String exchangeDshAuthCookie() {
    return lanAuth.exchange(lifecycle.generation());
  }

  public void stopWeb() {
    try {
      enqueueStop(null).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      Log.w("DSHA", com.deepseekharness.app.util.UiText.text("等待停止失败"), e);
    }
  }

  /** 立即禁用自动拉起，实际停止在共享队列执行，回调在后台线程。 */
  public void stopWeb(Consumer<String> onStatus) {
    enqueueStop(onStatus);
  }

  static boolean forceStopAllowed(
      WebLifecycle lifecycle, WebStopCoordinator.Result lastResult, long expectedGeneration) {
    synchronized (lifecycle) {
      return expectedGeneration > 0
          && lifecycle.generation() == expectedGeneration
          && lifecycle.isUserStopped()
          && !lifecycle.isStarting()
          && !lifecycle.isStopping()
          && lastResult != null
          && !lastResult.stopped();
    }
  }

  HarnessController.ForceStopRequest prepareForceStop() throws IOException {
    long generation;
    synchronized (lifecycle) {
      generation = lifecycle.generation();
      if (!forceStopAllowed(lifecycle, state.lastStopResult, generation))
        throw new com.deepseekharness.app.util.WebStopException(
            com.deepseekharness.app.util.WebStopDiagnostic.Code.FORCE_REQUIRES_UNCONFIRMED_STOP);
    }
    WebProcessManager.ForceCandidate candidate = webProc.prepareForceStop();
    synchronized (lifecycle) {
      if (!forceStopAllowed(lifecycle, state.lastStopResult, generation))
        throw new com.deepseekharness.app.util.WebStopException(
            com.deepseekharness.app.util.WebStopDiagnostic.Code.FORCE_REQUEST_CHANGED);
      return candidate == null
          ? null
          : new HarnessController.ForceStopRequest(generation, candidate);
    }
  }

  boolean forceStop(HarnessController.ForceStopRequest request, Consumer<String> status) {
    if (request == null || request.candidate() == null) return false;
    synchronized (lifecycle) {
      if (!forceStopAllowed(lifecycle, state.lastStopResult, request.generation())) return false;
      enqueueStop(status, request.candidate());
      return true;
    }
  }

  /** The main-thread delivery must still belong to this confirmed, completed stop. */
  static Runnable foregroundRetirement(
      WebLifecycle lifecycle, long generation, WebStopCoordinator.Result proof, Runnable retire) {
    return () -> {
      synchronized (lifecycle) {
        if (proof == null
            || !proof.stopped()
            || !lifecycle.isCurrent(generation)
            || !lifecycle.isUserStopped()
            || lifecycle.isStarting()
            || lifecycle.isStopping()) return;
        retire.run();
      }
    };
  }

  /** A failed attempt replaces any older exit proof, including failures after guest exit. */
  static WebStopCoordinator.Result recordStopFailure(HarnessSessionState state, Throwable failure) {
    var result =
        new WebStopCoordinator.Result(
            WebStopCoordinator.Status.FAILED,
            "WEB_STOP_FAILED",
            java.util.List.of(
                com.deepseekharness.app.util.WebStopDiagnostic.failure(
                    com.deepseekharness.app.util.WebStopDiagnostic.Code.STOP_FAILED, failure)));
    if (result.detail().isBlank())
      result =
          new WebStopCoordinator.Result(
              WebStopCoordinator.Status.FAILED,
              "WEB_STOP_FAILED",
              java.util.List.of(
                  com.deepseekharness.app.util.WebStopDiagnostic.of(
                      com.deepseekharness.app.util.WebStopDiagnostic.Code.STOP_FAILED)));
    state.lastStopResult = result;
    state.lastStopError = result.detail();
    return result;
  }

  private Future<?> enqueueStop(Consumer<String> onStatus) {
    return enqueueStop(onStatus, null);
  }

  private Future<?> enqueueStop(
      Consumer<String> onStatus, WebProcessManager.ForceCandidate forcedCandidate) {
    screenLifetime.revoke();
    synchronized (lifecycle) {
      if (lifecycle.isStopping()) return state.stopTask;
      long previous = lifecycle.generation();
      state.diagnostics.completed(previous, "stopped", "");
      long generation = lifecycle.beginStop();
      state.lastStopError = "";
      state.lastStopResult =
          new WebStopCoordinator.Result(
              WebStopCoordinator.Status.UNCONFIRMED, "WEB_STOP_PENDING", "");
      WebRun run = webRuns.get(previous);
      if (run != null) {
        run.authUrl = "";
        if (run.registration != null) run.registration.revoke();
      }
      notifyReadyWebPageChanged();
      // 宿主直接写小标记，不等可能仍在解压/注册插件的串行任务。
      try {
        File sentinel = stopSentinel();
        if (sentinel.getParentFile().isDirectory()) sentinel.createNewFile();
      } catch (Exception e) {
        Log.w("DSHA", com.deepseekharness.app.util.UiText.text("写停止标记失败，将由停止脚本重试"), e);
      }
      state.stopTask =
          io.submit(
              () -> {
                String stopError = "";
                WebStopCoordinator.Result retirementProof = null;
                try {
                  processes.loadStopEvidence();
                  // Core HTTP/ADB close and exit probes must never run in an Activity/Service
                  // callback. The epoch/permit revocation above has already fenced input.
                  screenLifetime.stop();
                  WebStopCoordinator.Result result =
                      WebStopCoordinator.stop(
                          new WebStopCoordinator.Ports() {
                            public String stopGuest() throws IOException {
                              return WebStopText.renderAll(stopGuestFacts());
                            }

                            public java.util.List<com.deepseekharness.app.util.WebStopDiagnostic>
                                stopGuestFacts() throws IOException {
                              return (forcedCandidate == null
                                      ? webProc.stopResult()
                                      : webProc.forceStopResult(forcedCandidate))
                                  .diagnostics();
                            }

                            public String stopOwnedLaunchers() {
                              return processes.stopOwnedLaunchers();
                            }

                            public java.util.List<com.deepseekharness.app.util.WebStopDiagnostic>
                                stopOwnedLauncherFacts() {
                              return processes.stopOwnedLauncherFacts();
                            }

                            public boolean confirm() throws IOException {
                              return processes.confirmStopped();
                            }
                          });
                  state.lastStopResult = result;
                  stopError = result.stopped() ? "" : result.detail();
                  if (stopError.isEmpty()) processes.releaseBridge(previous);
                  if (stopError.isEmpty()) {
                    try {
                      storedSession.clear();
                    } catch (IOException unavailable) {
                      DiagnosticLog.record(
                          ctx, "WEB_SESSION_RETIRE", unavailable.getClass().getSimpleName());
                    }
                    state.retainedWebStopProof = null;
                    state.unreadableWebStopProof = false;
                  }
                  if (stopError.isEmpty()
                      && !com.deepseekharness.app.core.MaintenanceCoordinator
                          .isEnvironmentTaskBusy()) {
                    com.deepseekharness.app.backup.AutomaticBackups.stopped(ctx);
                    com.deepseekharness.app.backup.PostUpgradeCleanupService.schedule(ctx);
                  } // Guest and owned-launcher proof remain separate.
                  com.deepseekharness.app.LanProxyService.stop(previous);
                  if (stopError.isEmpty()) retirementProof = result;
                } catch (RuntimeException | Error failure) {
                  retirementProof = null;
                  stopError = recordStopFailure(state, failure).detail();
                  if (failure instanceof Error) throw (Error) failure;
                } finally {
                  synchronized (lifecycle) {
                    state.lastStopError = stopError == null ? "" : stopError;
                    lifecycle.finishStop(generation);
                    if (retirementProof != null && retirementProof.stopped())
                      new android.os.Handler(android.os.Looper.getMainLooper())
                          .post(
                              foregroundRetirement(
                                  lifecycle,
                                  generation,
                                  retirementProof,
                                  () ->
                                      com.deepseekharness.app.HarnessService.retireStoppedRuntime(
                                          ctx, generation)));
                    reportStatus(
                        generation,
                        onStatus,
                        stopError.isEmpty()
                            ? com.deepseekharness.app.util.UiText.text("停止操作已完成")
                            : stopError);
                  }
                }
              });
      return state.stopTask;
    }
  }

  private File stopSentinel() {
    return new File(proot.getRootfsDir(), WebProcSel.pidFileRel(WebProcSel.STOP_SENTINEL));
  }

  private boolean hasStopSentinel() {
    return stopSentinel().exists();
  }

  public boolean isStarting() {
    return lifecycle.isStarting();
  }

  public boolean isStopping() {
    return lifecycle.isStopping();
  }

  public boolean isUserStopped() {
    return lifecycle.isUserStopped();
  }

  public boolean canAutoRestart() {
    boolean recoveryRequested = config.isStartupRecoveryRequested();
    boolean stopSentinelPresent = hasStopSentinel();
    synchronized (lifecycle) {
      WebRun run = state.currentRun;
      return !state.restoreUnknown
          && !(run != null && run.recovered != null && !run.recoveredLive)
          && !recoveryRequested
          && !state.recovery.blocked()
          && lifecycle.canAutoStart(stopSentinelPresent);
    }
  }

  public boolean isRestartBlocked() {
    synchronized (lifecycle) {
      return state.recovery.blocked();
    }
  }

  public void reportWebHealth(long generation, boolean healthy) {
    synchronized (lifecycle) {
      if (!lifecycle.isCurrent(generation)) return;
      if (healthy) state.recovery.healthy(generation, android.os.SystemClock.elapsedRealtime());
      else state.recovery.unhealthy(generation);
    }
  }

  private void setWebStage(long generation, String stage) {
    synchronized (lifecycle) {
      if (lifecycle.isCurrent(generation)) {
        state.recovery.stage(generation, stage);
        state.diagnostics.stage(generation, stage);
      }
    }
  }

  public StartupDiagnostics startupDiagnostics() {
    return state.diagnostics;
  }

  /** 用户主动恢复：停止当前任务后再启动，慢启动也能使用此入口。 */
  public boolean recoverWebIfUnchanged(
      long expectedGeneration, boolean safe, String disabledPlugin, Consumer<String> onStatus) {
    synchronized (lifecycle) {
      if (lifecycle.generation() != expectedGeneration) return false;
      recoverWeb(safe, disabledPlugin, onStatus);
      return true;
    }
  }

  public void recoverWeb(boolean safe, String disabledPlugin, Consumer<String> onStatus) {
    if (disabledPlugin != null
        && (!state.diagnostics.snapshot().issues.containsKey(disabledPlugin)
            || com.deepseekharness.app.util.BuiltinPlugins.internal(disabledPlugin))) return;
    // enqueueStop() 会立即把生命周期置为 stopping，并把真正的停止放入同一条 IO 队列。
    // 旧实现随后马上检查 isStopping()，因此恢复入口总是在停止任务尚未完成时直接返回，
    // 既没有第二次启动，也没有给主界面留下可解释的错误。必须等待这一次 Future 完成，
    // 再在相同 generation 上排入启动；不复用旧进程，也不改变用户数据。
    Future<?> stop = enqueueStop(null);
    final long stopped = lifecycle.generation();
    io.execute(
        () -> {
          try {
            if (stop != null) stop.get();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            reportStatus(
                stopped, onStatus, com.deepseekharness.app.util.UiText.text("等待停止被中断，请重试"));
            return;
          } catch (Exception error) {
            reportStatus(
                stopped,
                onStatus,
                com.deepseekharness.app.util.UiText.format(
                    "停止旧 Web 失败：%s",
                    com.deepseekharness.app.util.SensitiveData.redact(
                        String.valueOf(error.getMessage()))));
            return;
          }
          if (!lifecycle.isCurrent(stopped) || lifecycle.isStopping()) return;
          if (disabledPlugin != null) {
            com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
                com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(
                    com.deepseekharness.app.util.UiText.text("停用故障插件"));
            if (lease == null) {
              reportStatus(
                  stopped,
                  onStatus,
                  com.deepseekharness.app.util.UiText.text("正在进行环境任务，完成后再停用插件。"));
              return;
            }
            try (lease) {
              String result = lease.run(() -> proot.setPluginEnabled(disabledPlugin, false));
              if (!result.contains("BUILTIN_REGISTER_OK")) {
                reportStatus(
                    stopped,
                    onStatus,
                    com.deepseekharness.app.util.UiText.format("停用插件失败：%s", result));
                return;
              }
            } catch (Exception error) {
              reportStatus(
                  stopped,
                  onStatus,
                  com.deepseekharness.app.util.UiText.format("停用插件失败：%s", error.getMessage()));
              return;
            }
          }
          requestStart(onStatus, false, 0, safe, stopped);
        });
  }

  private void recordWebFailure(long generation, String reason) {
    synchronized (lifecycle) {
      if (lifecycle.isCurrent(generation))
        state.recovery.stage(generation, state.diagnostics.snapshot().stage);
      if (!lifecycle.isCurrent(generation) || !state.recovery.fail(generation, reason)) return;
      notifyReadyWebPageChanged();
      state.diagnostics.completed(generation, "failed", reason);
      WebRun failedRun = webRuns.get(generation);
      if (failedRun != null && failedRun.registration != null) failedRun.registration.revoke();
      state.diagnostics.preserveFailure(new File(proot.getRootfsDir(), "root/dsh-web.log"), reason);
      if (!state.diagnostics.snapshot().safe)
        PluginActivationHooks.failed(owner, generation, state.diagnostics.recordId());
      if (!state.diagnostics.snapshot().browserReady || state.recovery.blocked())
        config.requestStartupRecovery(true);
      DiagnosticLog.record(
          ctx,
          "WEB_FAILURE",
          config.getWebFailureStage() + com.deepseekharness.app.util.UiText.text("：") + reason);
      if (state.recovery.blocked()) {
        // 立即撤销失败代次；停止仍只使用哨兵和 Web PID，不杀容器启动器。
        stopWeb(null);
      }
    }
  }

  /** 明确的网页启动失败进入原生恢复；慢启动与普通控制台错误不调用此入口。 */
  public void failedWebPage(long generation, String reason) {
    synchronized (lifecycle) {
      if (!lifecycle.isCurrent(generation)) return;
      recordWebFailure(generation, reason);
      config.requestStartupRecovery(true);
      stopWeb(null);
    }
  }

  /** 仅给当前仍在启动的代次提示，不累计失败、不写停止标记、不终止进程。 */
  void reportSlowStart(long generation, Consumer<String> onStatus) {
    synchronized (lifecycle) {
      if (lifecycle.isCurrent(generation) && lifecycle.isStarting())
        reportStatus(
            generation,
            onStatus,
            com.deepseekharness.app.util.UiText.text("启动时间较长，仍在等待鉴权；可查看日志或手动停止"));
    }
  }

  /** 发布前检查代次；UI 入队后还要再检查，防主线程消费到旧消息。 */
  private void reportStatus(long generation, Consumer<String> onStatus, String message) {
    synchronized (lifecycle) {
      if (!lifecycle.isCurrent(generation)) return;
      DiagnosticLog.record(ctx, "WEB_START_STOP", message);
      state.diagnostics.message(generation, message);
      if (onStatus == null) return;
      try {
        onStatus.accept(message);
      } catch (RuntimeException e) {
        Log.w("DSHA", com.deepseekharness.app.util.UiText.text("启动状态回调失败"), e);
      }
    }
  }

  /** 当前 dsh 代次号（供 LAN 代理 / 配置页开关联动）。 */
  public long getWebGeneration() {
    return lifecycle.generation();
  }
}
