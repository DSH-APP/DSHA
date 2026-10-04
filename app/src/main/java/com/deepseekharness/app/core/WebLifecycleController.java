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

  static final class WebRun {
    final long generation;
    volatile Process launcher;
    volatile WebProcessSession.BridgeLease bridge;
    volatile String authUrl = "";
    volatile int port;
    volatile boolean compatible;

    WebRun(long generation) {
      this.generation = generation;
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

  WebLifecycleController(HarnessController owner, HarnessSessionState state) {
    this.owner = owner;
    this.ctx = owner.context();
    this.config = owner.config();
    this.proot = owner.proot();
    this.webProc = new WebProcessManager(proot);
    this.state = state;
    this.lifecycle = state.lifecycle;
    this.io = state.io;
    this.webRuns = state.runs;
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

  /** Web 是否在运行（按 pid 文件 + kill -0 判断，不依赖端口反查）。 */
  public boolean isWebRunning() {
    return processes.running();
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
    return run == null ? "" : run.authUrl;
  }

  /** dsh 实际启动命令（写 pid 文件要在 exec 之前，exec 不换 pid）。 */
  public String runCoreCommand() {
    return runCoreCommand("web", config.getPortInt(), getWebGeneration());
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

  /** 看门狗不能撤销用户停止意图；检查与入队在同一把锁内完成。 */
  public boolean restartWebAutomatically(long expectedGeneration, Consumer<String> onStatus) {
    synchronized (lifecycle) {
      if (expectedGeneration != lifecycle.generation() || !canAutoRestart()) return false;
      recordWebFailure(
          expectedGeneration, com.deepseekharness.app.util.UiText.text("服务连续三次健康检查未响应"));
      if (state.recovery.blocked()) return false;
    }
    return requestStart(onStatus, true, expectedGeneration, state.diagnostics.snapshot().safe);
  }

  private boolean requestStart(
      Consumer<String> onStatus, boolean automatic, long expectedGeneration, boolean safeMode) {
    synchronized (lifecycle) {
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
        final long generation = lifecycle.beginStart(automatic, hasStopSentinel());
        if (generation < 0) return false;
        startedGeneration = generation;
        boolean priorCompatible = state.currentRun != null && state.currentRun.compatible;
        WebRun run = new WebRun(generation);
        state.currentRun = run;
        webRuns.put(generation, run);
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
      String runtimeName = boot.runtime().id();
      state.diagnostics.message(
          generation, com.deepseekharness.app.util.UiText.format("本次实际运行方式：%s", runtimeName));
      run.compatible = compatible;
      int preferred = config.getPortInt();
      com.deepseekharness.app.util.WebPortPolicy.Choice ports =
          com.deepseekharness.app.util.WebPortPolicy.choose(
              preferred,
              config.fallbackWebPort(preferred),
              com.deepseekharness.app.util.WebPortPolicy::available);
      if (!lifecycle.isCurrent(generation)) return;
      run.port = ports.listen;
      if (ports.fallback()) {
        reportStatus(
            generation,
            onStatus,
            com.deepseekharness.app.util.UiText.format("首选 Web 端口已占用：%s", preferred));
        reportStatus(
            generation,
            onStatus,
            com.deepseekharness.app.util.UiText.text("正在自动选择可用 Web 端口，首选端口设置保留"));
      }
      String launchKey = config.readApiKey().requireValue();
      Map<String, String> launchEnvironment =
          launchKey.isEmpty() ? Map.of() : Map.of("DEEPSEEK_API_KEY", launchKey);
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
                    if (ports.fallback()) {
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
    com.deepseekharness.app.HttpShellService.revokeScreenGrant(ctx);
    com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).stop();
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

  private Future<?> enqueueStop(Consumer<String> onStatus) {
    return enqueueStop(onStatus, null);
  }

  private Future<?> enqueueStop(
      Consumer<String> onStatus, WebProcessManager.ForceCandidate forcedCandidate) {
    com.deepseekharness.app.HttpShellService.revokeScreenGrant(ctx);
    com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).stop();
    synchronized (lifecycle) {
      if (lifecycle.isStopping()) return state.stopTask;
      long previous = lifecycle.generation();
      state.diagnostics.completed(previous, "stopped", "");
      long generation = lifecycle.beginStop();
      state.lastStopError = "";
      WebRun run = webRuns.get(previous);
      if (run != null) run.authUrl = "";
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
                try {
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
                  if (stopError.isEmpty()
                      && !com.deepseekharness.app.core.MaintenanceCoordinator
                          .isEnvironmentTaskBusy()) {
                    com.deepseekharness.app.backup.AutomaticBackups.stopped(ctx);
                    com.deepseekharness.app.backup.PostUpgradeCleanupService.schedule(ctx);
                  } // Guest and owned-launcher proof remain separate.
                  com.deepseekharness.app.LanProxyService.stop(previous);
                } finally {
                  synchronized (lifecycle) {
                    state.lastStopError = stopError == null ? "" : stopError;
                    lifecycle.finishStop(generation);
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
    synchronized (lifecycle) {
      return !config.isStartupRecoveryRequested()
          && !state.recovery.blocked()
          && lifecycle.canAutoStart(hasStopSentinel());
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
          requestStart(onStatus, false, 0, safe);
        });
  }

  private void recordWebFailure(long generation, String reason) {
    synchronized (lifecycle) {
      if (lifecycle.isCurrent(generation))
        state.recovery.stage(generation, state.diagnostics.snapshot().stage);
      if (!lifecycle.isCurrent(generation) || !state.recovery.fail(generation, reason)) return;
      state.diagnostics.completed(generation, "failed", reason);
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
