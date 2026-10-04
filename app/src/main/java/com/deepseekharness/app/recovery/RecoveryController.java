package com.deepseekharness.app.recovery;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import com.deepseekharness.app.util.DshAuthSession;
import com.deepseekharness.app.util.DshAuthUrl;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.RuntimeInstanceRegistry;
import com.deepseekharness.app.util.RuntimeInstanceReadiness;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.WebPidIdentity;
import com.deepseekharness.app.util.UiText;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 与正式 HarnessController 完全分开的应急生命周期；正式维护失败不会阻止准备和只读诊断。 */
public final class RecoveryController {
  private static volatile RecoveryController instance;
  private final Context context;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "dsha-recovery");
            thread.setDaemon(true);
            return thread;
          });
  private volatile Snapshot snapshot =
      new Snapshot(
          "STOPPED",
          UiText.choose("应急 DSH 尚未启动", "Recovery DSH has not started"),
          "",
          "",
          "",
          "",
          0,
          "",
          false,
          false);
  private volatile RecoveryRepairBroker broker;
  private volatile RecoveryRepairBroker lastBroker;
  private volatile Process process;
  private volatile RecoveryRuntime runtime;
  private volatile boolean cancel, resetBlocked;
  private long nextGeneration;
  private PowerManager.WakeLock wake;

  public static RecoveryController get(Context context) {
    if (instance == null)
      synchronized (RecoveryController.class) {
        if (instance == null) instance = new RecoveryController(context.getApplicationContext());
      }
    return instance;
  }

  private RecoveryController(Context context) {
    this.context = context;
  }

  public static final class Snapshot {
    public final String state, detail, errorCode, authUrl, cookie, baseUrl, instanceId;
    public final long generation;
    public final boolean ready, busy, live, modelCredentialAvailable;

    private Snapshot(
        String state,
        String detail,
        String errorCode,
        String authUrl,
        String cookie,
        String baseUrl,
        long generation,
        String instanceId,
        boolean live,
        boolean modelCredentialAvailable) {
      this.state = state;
      this.detail = detail;
      this.errorCode = errorCode;
      this.authUrl = authUrl;
      this.cookie = cookie;
      this.baseUrl = baseUrl;
      this.generation = generation;
      this.instanceId = instanceId;
      this.live = live;
      this.modelCredentialAvailable = modelCredentialAvailable;
      ready = state.equals("READY") || state.equals("READY_READ_ONLY");
      busy =
          state.equals("PREPARING")
              || state.equals("STARTING")
              || state.equals("STOPPING")
              || state.equals("STOP_UNCONFIRMED");
    }
  }

  public Snapshot snapshot() {
    return snapshot;
  }

  public RecoveryRepairBroker broker() {
    return broker != null ? broker : lastBroker;
  }

  /** API Key 只进入本次进程环境；不会写入正式 ConfigStore、日志或应急 profile。 */
  public synchronized boolean start(String temporaryApiKey) {
    if (resetBlocked
        || snapshot.busy
        || snapshot.ready
        || process != null
        || RecoveryRepairBroker.activeNativeRepairs() > 0) return false;
    cancel = false;
    runtime = null;
    long generation = ++nextGeneration;
    String id = UUID.randomUUID().toString().replace("-", "");
    publish(
        generation,
        id,
        "PREPARING",
        UiText.choose("正在准备独立应急 DSH…", "Preparing independent recovery DSH…"),
        "",
        "",
        "",
        "");
    String[] key = {temporaryApiKey};
    worker.execute(() -> run(generation, id, key));
    return true;
  }

  /** 非阻塞请求；只有快照 STOPPED 才代表 guest 与启动器都已核验退出。 */
  public synchronized boolean stop() {
    if (snapshot.state.equals("STOPPED") || (snapshot.state.equals("FAILED") && process == null))
      return true;
    cancel = true;
    long generation = snapshot.generation;
    String id = snapshot.instanceId;
    if (snapshot.state.equals("STOP_UNCONFIRMED")) {
      publish(
          generation,
          id,
          "STOPPING",
          UiText.choose("正在重新核验应急进程退出…", "Checking whether the recovery process exited…"),
          "",
          "",
          "",
          "");
      worker.execute(() -> finishStop(generation, id, "", false));
    } else if (!snapshot.state.equals("STOPPING")) {
      publish(
          generation,
          id,
          "STOPPING",
          UiText.choose(
              "正在停止应急 DSH，原会话将保留…", "Stopping recovery DSH; its session will be retained…"),
          "",
          "",
          "",
          "");
    }
    return true;
  }

  /** 完整格式化先关闭这一独立写者。调用方在结束格式化作用域后释放 gate。 */
  public void stopAndWaitForReset() throws IOException {
    synchronized (this) {
      resetBlocked = true;
      stop();
    }
    long deadline = SystemClock.elapsedRealtime() + 15_000;
    while (SystemClock.elapsedRealtime() < deadline) {
      Snapshot state = snapshot;
      if (!state.busy && !state.live && !state.ready && process == null) {
        RecoveryRuntime.recoverInterrupted(context, () -> Thread.currentThread().isInterrupted());
        return;
      }
      if (state.state.equals("STOP_UNCONFIRMED"))
        throw new IOException("RECOVERY_PROCESS_UNCONFIRMED");
      try {
        Thread.sleep(100);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("RECOVERY_RESET_CANCELLED");
      }
    }
    throw new IOException("RECOVERY_STOP_TIMEOUT");
  }

  public synchronized void releaseResetGate() {
    resetBlocked = false;
  }

  private void run(long generation, String id, String[] key) {
    String failure = "";
    String credentialNotice = "";
    try {
      renewWake();
      RecoveryRuntime.recoverInterrupted(context, () -> cancel || !current(generation));
      if (key[0] == null) {
        com.deepseekharness.app.util.CredentialRead credential =
            new com.deepseekharness.app.core.ConfigStore(context).readApiKey();
        if (credential.state == com.deepseekharness.app.util.CredentialRead.State.AVAILABLE)
          key[0] = credential.requireValue();
        else {
          key[0] = "";
          credentialNotice =
              credential.state == com.deepseekharness.app.util.CredentialRead.State.NOT_CONFIGURED
                  ? UiText.choose(
                      " 尚未配置模型凭据，可返回原生应急页面填写本次临时密钥。",
                      " No model credential is configured. Enter a temporary key on the native recovery page.")
                  : UiText.choose(
                      " 原生凭据暂不可读取，已保留原密文；可填写本次临时密钥。",
                      " The saved credential is unavailable and has been preserved. You can enter a temporary key.");
        }
      }
      RecoveryRuntime created =
          new RecoveryRuntime(
              context,
              id,
              () -> cancel || !current(generation),
              detail -> {
                if (!cancel) publish(generation, id, "PREPARING", detail, "", "", "", "");
              });
      runtime = created;
      created.prepare();
      created.prepareProfile();
      boolean assetsVerified = true;
      if (cancel || !current(generation)) return;
      broker = RecoveryRepairBroker.createSession(context, id, generation);
      boolean credentialAvailable = key[0] != null && !key[0].trim().isEmpty();
      String outputSecret = key[0];
      Process launched = created.launch(key[0], generation, broker);
      key[0] = "";
      process = launched;
      boolean launcherVerified = false;
      try {
        created.recordLauncher(launched);
        launcherVerified = true;
      } catch (RecoveryRuntime.IdentityUnavailable unavailable) {
        // 部分 ROM 可启动并连入自己的子进程，却禁止读取 /proc；等真实鉴权/工具通过再评估只读入口。
      }
      publish(
          generation,
          id,
          "STARTING",
          UiText.choose(
              "正在启动空白 DSH，等待受控工具检查与鉴权…",
              "Starting a blank DSH; waiting for tool verification and authentication…"),
          "",
          "",
          "",
          "");
      Output output = new Output(launched, id, outputSecret, broker.token());
      outputSecret = "";
      DshAuthSession.Result exchange = null;
      long started = SystemClock.elapsedRealtime(), renewed = started;
      while (!cancel && current(generation)) {
        output.drain();
        if (!output.failure.isEmpty()) throw new IOException(output.failure);
        if (ProcessTermination.exited(launched))
          throw new IOException(
              "RECOVERY_PROCESS_EXITED_" + launched.exitValue() + ": " + output.tail());
        if (output.auth != null && output.agentReady && exchange == null) {
          DshAuthUrl.Parsed auth = output.auth;
          exchange =
              DshAuthSession.exchange(
                  auth.authUrl,
                  URI.create(auth.loopbackBaseUrl).getPort(),
                  () -> !cancel && current(generation));
          if (exchange.status == DshAuthSession.Status.NOT_READY) exchange = null;
          else if (!exchange.ready()) throw new IOException("RECOVERY_AUTH_" + exchange.status);
          else {
            WebPidIdentity identity = null;
            String command = "";
            RuntimeInstanceReadiness.Identity identityEvidence;
            try {
              if (!launcherVerified) created.recordLauncher(launched);
              identity = created.verifiedIdentity();
              command = created.command(identity.pid);
              identityEvidence = RuntimeInstanceReadiness.Identity.VERIFIED;
            } catch (RecoveryRuntime.IdentityUnavailable unavailable) {
              identityEvidence = RuntimeInstanceReadiness.Identity.UNAVAILABLE;
            }
            RuntimeInstanceReadiness.Result readiness =
                RuntimeInstanceReadiness.decide(
                    assetsVerified,
                    exchange.ready(),
                    output.agentReady,
                    !ProcessTermination.exited(launched),
                    identityEvidence);
            if (readiness == RuntimeInstanceReadiness.Result.BLOCKED)
              throw new IOException("RECOVERY_READY_EVIDENCE_INCOMPLETE");
            if (readiness == RuntimeInstanceReadiness.Result.READY_READ_ONLY) {
              String reason =
                  UiText.choose(
                      "系统限制进程身份读取；应急对话与只读诊断可用，正式环境写入仍被阻止。",
                      "The system restricts process identity access. Recovery chat and read-only diagnostics are available; writes to the main environment remain blocked.");
              broker.blockWrites(reason);
              created.recordOutcome("READY_READ_ONLY", reason);
              publish(
                  generation,
                  id,
                  "READY_READ_ONLY",
                  reason + credentialNotice,
                  "RECOVERY_IDENTITY_READ_ONLY",
                  auth.authUrl,
                  exchange.cookie,
                  auth.loopbackBaseUrl,
                  credentialAvailable);
            } else {
              RuntimeInstanceRegistry.shared()
                  .registerRecovery(id, generation, identity, command, true);
              created.recordOutcome("READY", "");
              publish(
                  generation,
                  id,
                  "READY",
                  UiText.choose(
                          "独立应急 DSH 已就绪；正式环境保持原状。",
                          "Independent recovery DSH is ready. The main environment is preserved.")
                      + credentialNotice,
                  "",
                  auth.authUrl,
                  exchange.cookie,
                  auth.loopbackBaseUrl,
                  credentialAvailable);
            }
          }
        }
        if (exchange == null && SystemClock.elapsedRealtime() - started > 60_000)
          publish(
              generation,
              id,
              "STARTING",
              UiText.choose(
                  "应急 DSH 仍在启动，可继续等待或停止；详细原因显示在本页。",
                  "Recovery DSH is still starting. You can wait or stop it; details remain available here."),
              "",
              "",
              "",
              "");
        if (SystemClock.elapsedRealtime() - renewed > 300_000) {
          renewWake();
          renewed = SystemClock.elapsedRealtime();
        }
        Thread.sleep(150);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failure = "RECOVERY_CANCELLED";
    } catch (Exception error) {
      failure = SensitiveData.redact(String.valueOf(error.getMessage()));
      if (failure == null || failure.isEmpty()) failure = "RECOVERY_UNAVAILABLE";
    } finally {
      key[0] = "";
      finishStop(generation, id, failure, !cancel && !failure.isEmpty());
    }
  }

  private void finishStop(long generation, String id, String failure, boolean failed) {
    if (!current(generation)) return;
    // 先撤销工具访问。进程未确认退出时也不能保留能继续提出新修复的桥。
    RecoveryRepairBroker closing = broker;
    if (closing != null) lastBroker = closing;
    broker = null;
    if (closing != null) closing.close();
    RuntimeInstanceRegistry.shared().remove(id, generation);
    Process active = process;
    try {
      if (active != null && (runtime == null || !runtime.stop(active))) {
        publish(
            generation,
            id,
            "STOP_UNCONFIRMED",
            UiText.choose(
                "尚未确认应急进程退出，现场已保留；可重试停止。",
                "Recovery process exit is unconfirmed. Records are preserved; you can retry stopping."),
            "RECOVERY_PROCESS_UNCONFIRMED",
            "",
            "",
            "");
        return;
      }
      process = null;
      if (failed)
        publish(
            generation,
            id,
            "FAILED",
            UiText.format("应急 DSH 未就绪：%s", failure),
            errorCode(failure),
            "",
            "",
            "");
      else
        publish(
            generation,
            id,
            "STOPPED",
            UiText.choose(
                "应急 DSH 已停止，应急会话及诊断原件已保留。",
                "Recovery DSH stopped. Its session and original diagnostics are preserved."),
            "",
            "",
            "",
            "");
    } catch (Exception stopping) {
      publish(
          generation,
          id,
          "STOP_UNCONFIRMED",
          UiText.format("应急进程退出尚未确认：%s", SensitiveData.redact(stopping.getMessage())),
          "RECOVERY_PROCESS_UNCONFIRMED",
          "",
          "",
          "");
    } finally {
      if (runtime != null)
        try {
          runtime.recordOutcome(snapshot.state, snapshot.detail);
        } catch (IOException recording) {
          android.util.Log.w(
              "DSHA", "RECOVERY_OUTCOME_UNAVAILABLE: " + recording.getClass().getSimpleName());
        }
      releaseWake();
    }
  }

  private static String errorCode(String detail) {
    String code = detail.split("[:\\s]", 2)[0];
    return code.matches("RECOVERY_[A-Z0-9_]+") ? code : "RECOVERY_UNAVAILABLE";
  }

  private synchronized boolean current(long generation) {
    return snapshot.generation == generation;
  }

  private synchronized void publish(
      long generation,
      String id,
      String state,
      String detail,
      String error,
      String auth,
      String cookie,
      String base) {
    publish(generation, id, state, detail, error, auth, cookie, base, false);
  }

  private synchronized void publish(
      long generation,
      String id,
      String state,
      String detail,
      String error,
      String auth,
      String cookie,
      String base,
      boolean credentialAvailable) {
    if (snapshot.generation > generation) return;
    if (snapshot.generation != generation || !snapshot.state.equals(state)) {
      String port = "";
      if (!base.isEmpty())
        try {
          port = " port=" + URI.create(base).getPort();
        } catch (IllegalArgumentException ignored) {
        }
      com.deepseekharness.app.core.DiagnosticLog.record(
          context,
          "RECOVERY_" + state,
          "instance="
              + id
              + " generation="
              + generation
              + port
              + " previousStage="
              + SensitiveData.redact(snapshot.detail)
              + "\n"
              + SensitiveData.redact(detail));
    }
    snapshot =
        new Snapshot(
            state,
            detail,
            error,
            auth,
            cookie,
            base,
            generation,
            id,
            process != null,
            credentialAvailable);
  }

  private void renewWake() {
    try {
      if (wake == null) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSHA:recovery");
        wake.setReferenceCounted(false);
      }
      wake.acquire(600_000);
    } catch (RuntimeException ignored) {
    }
  }

  private void releaseWake() {
    if (wake != null)
      try {
        if (wake.isHeld()) wake.release();
      } catch (RuntimeException ignored) {
      }
  }

  private static final class Output {
    final InputStream input;
    final String id;
    final String secret, bridgeSecret;
    final ByteArrayOutputStream line = new ByteArrayOutputStream();
    final StringBuilder history = new StringBuilder();
    DshAuthUrl.Parsed auth;
    boolean agentReady;
    String failure = "";

    Output(Process process, String id, String secret, String bridgeSecret) {
      input = process.getInputStream();
      this.id = id;
      this.secret = secret == null ? "" : secret;
      this.bridgeSecret = bridgeSecret == null ? "" : bridgeSecret;
    }

    String tail() {
      return history.toString();
    }

    void drain() throws IOException {
      byte[] bytes = new byte[8192];
      int budget = 256 * 1024;
      while (input.available() > 0 && budget > 0) {
        int n = input.read(bytes, 0, Math.min(bytes.length, Math.min(input.available(), budget)));
        if (n < 0) break;
        budget -= n;
        for (int i = 0; i < n; i++) {
          if (bytes[i] == '\n') {
            String text = new String(line.toByteArray(), StandardCharsets.UTF_8).trim();
            line.reset();
            if (text.equals("DSHA_RECOVERY_AGENT_READY:" + id)) agentReady = true;
            if (text.startsWith("DSHA_RECOVERY_AGENT_FAILED"))
              failure = "RECOVERY_TOOL_CHECK_FAILED";
            if (auth == null) auth = DshAuthUrl.fromStartupOutput(text);
            String safe = SensitiveData.redact(DshAuthUrl.redact(text));
            if (!secret.isEmpty()) safe = safe.replace(secret, "***");
            if (!bridgeSecret.isEmpty()) safe = safe.replace(bridgeSecret, "***");
            history.append(safe).append('\n');
            if (history.length() > 8192) history.delete(0, history.length() - 8192);
          } else {
            if (line.size() > 65536) throw new IOException("RECOVERY_OUTPUT_LIMIT");
            line.write(bytes[i]);
          }
        }
      }
    }
  }
}
