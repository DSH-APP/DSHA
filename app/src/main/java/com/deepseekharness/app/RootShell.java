package com.deepseekharness.app;

import android.content.Context;
import android.util.Base64;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.RootShellCandidates;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Root 直接通道；仅在用户开启开关并执行设备操作时请求 su，不需要 ADB。 */
public final class RootShell {
  private enum Status {
    WAITING,
    RESPONDED,
    TIMED_OUT,
    FAILED
  }

  private static final class LastResult {
    final Status status;
    final String executable, detail;

    LastResult(Status status, String executable, String detail) {
      this.status = status;
      this.executable = executable;
      this.detail = detail;
    }

    String render(String current) {
      if (!executable.equals(current) || status == Status.WAITING)
        return com.deepseekharness.app.util.UiText.format("su：%s\n尚未验证 root 授权", current);
      return switch (status) {
        case RESPONDED ->
            com.deepseekharness.app.util.UiText.format("su：%s\nroot 通道已响应 · 设备命令仍受策略保护", current);
        case TIMED_OUT ->
            com.deepseekharness.app.util.UiText.format(
                "su：%s\nroot 授权或执行超时，请在 root 管理器查看 DSHA 授权", current);
        case FAILED ->
            com.deepseekharness.app.util.UiText.format("su：%s\nroot 授权或执行失败：%s", current, detail);
        case WAITING -> throw new IllegalStateException("ROOT_STATUS_WAITING");
      };
    }
  }

  private static volatile LastResult lastResult = new LastResult(Status.WAITING, "", "");

  private RootShell() {}

  public static boolean enabled(Context ctx) {
    return ctx.getSharedPreferences(Constants.PREFS, 0)
        .getBoolean(Constants.KEY_ALLOW_ROOT_SHELL, false);
  }

  private static String executable() {
    // Only inspect known system/root-manager locations. Discovery never invokes
    // su or trusts an app-controlled PATH; authorization happens on execution.
    return RootShellCandidates.firstVerified(
        path -> {
          try {
            File file = new File(path);
            android.system.StructStat stat = android.system.Os.stat(path);
            int writable = android.system.OsConstants.S_IWGRP | android.system.OsConstants.S_IWOTH;
            return stat.st_uid == 0
                && android.system.OsConstants.S_ISREG(stat.st_mode)
                && (stat.st_mode & writable) == 0
                && file.canExecute();
          } catch (android.system.ErrnoException | SecurityException ignored) {
            return false;
          }
        });
  }

  public static boolean present() {
    return executable() != null;
  }

  public static String status(Context ctx) {
    if (!enabled(ctx)) return com.deepseekharness.app.util.UiText.text("未启用 · 开启后可直接申请 root 授权");
    String su = executable();
    String base =
        su == null
            ? com.deepseekharness.app.util.UiText.text("未找到 su，可使用 Shizuku 或 ADB 通道")
            : lastResult.render(su);
    return base + "\n" + com.deepseekharness.app.util.UiText.format("命令拦截档位：%s", policyLabel(ctx));
  }

  /** 当前档位的中文展示名；三个档位只影响 root 原始命令，不改变 SMS 等原生闸门。 */
  public static String policyLabel(Context ctx) {
    return switch (new com.deepseekharness.app.core.ConfigStore(ctx).rootPolicyMode()) {
      case STRICT -> com.deepseekharness.app.util.UiText.text("严格（默认）");
      case RELAXED -> com.deepseekharness.app.util.UiText.text("放宽（保留不可逆操作拦截）");
      case FULL -> com.deepseekharness.app.util.UiText.text("完全放宽（不按命令内容拦截）");
    };
  }

  public static String exec(Context ctx, String command, int authorizedSmsUser) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    String hostBlocked = com.deepseekharness.app.util.HostDeviceCommandGuard.rejection(command);
    if (hostBlocked != null) return hostBlocked + "\n[EXIT=126]";
    DeviceShellPolicy.Mode mode =
        new com.deepseekharness.app.core.ConfigStore(ctx).rootPolicyMode();
    DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command, mode);
    if (!plan.allowed()) return plan.reason + "\n[EXIT=126]";
    if (plan.kind == DeviceShellPolicy.Kind.SENSITIVE_READ
        && (authorizedSmsUser < 0
            || !new com.deepseekharness.app.core.DeviceGrants(ctx).smsReadAllowed()))
      return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 短信读取尚未经过原生授权\n[EXIT=126]");
    if (plan.kind == DeviceShellPolicy.Kind.SHELL) {
      // 原始 shell 只能经 root 通道执行；Shizuku/ADB 的通用执行器仍走严格档，不识别此计划。
      if (mode == DeviceShellPolicy.Mode.STRICT || plan.shell == null)
        return com.deepseekharness.app.util.UiText.text(
            "[POLICY_BLOCKED] ROOT_SHELL_PLAN_UNVERIFIED\n[EXIT=126]");
      return runPrivileged(ctx, plan.shell, -1, "root-unrestricted");
    }
    return runPrivileged(ctx, command, authorizedSmsUser, "");
  }

  /** Native-only launcher path. Generic RootShell.exec rejects app_process unconditionally. */
  public static String execVirtualScreen(Context ctx, String command) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    try {
      String source = ctx.getApplicationInfo().sourceDir;
      String canonical = new File(source).getCanonicalPath();
      if (!source.equals(canonical)
          || !DeviceShellPolicy.inspectManagedVirtualScreen(
                  command, canonical, ctx.getPackageName())
              .allowed())
        return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 虚拟屏启动参数无法核验\n[EXIT=126]");
    } catch (Exception invalid) {
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 无法核验 DSHA 安装包路径\n[EXIT=126]");
    }
    return runPrivileged(ctx, command, -1, "virtual-screen-start");
  }

  /** Preserve pre-send refusal separately from a possibly executed native launch/probe. */
  public static com.deepseekharness.app.device.DeviceTransportResult execVirtualScreenTyped(
      Context ctx, String command) {
    if (!enabled(ctx))
      return com.deepseekharness.app.device.DeviceTransportResult.notSent(
          "root", "ROOT_NOT_ENABLED", "[POLICY_BLOCKED] ROOT_NOT_ENABLED\n[EXIT=126]");
    try {
      String source = ctx.getApplicationInfo().sourceDir;
      String canonical = new File(source).getCanonicalPath();
      if (!source.equals(canonical)
          || !DeviceShellPolicy.inspectManagedVirtualScreen(
                  command, canonical, ctx.getPackageName())
              .allowed())
        return com.deepseekharness.app.device.DeviceTransportResult.notSent(
            "root",
            "DEVICE_POLICY_BLOCKED",
            "[POLICY_BLOCKED] VSCREEN_ARGUMENTS_UNVERIFIED\n[EXIT=126]");
    } catch (Exception invalid) {
      return com.deepseekharness.app.device.DeviceTransportResult.notSent(
          "root",
          "SELF_PACKAGE_UNVERIFIED",
          "[POLICY_BLOCKED] SELF_PACKAGE_UNVERIFIED\n[EXIT=126]");
    }
    if (executable() == null)
      return com.deepseekharness.app.device.DeviceTransportResult.notSent(
          "root", "ROOT_UNAVAILABLE", "[ROOT_UNAVAILABLE] ROOT_UNAVAILABLE\n[EXIT=124]");
    String output = runPrivileged(ctx, command, -1, "virtual-screen-start");
    // 槽位已满时 su 未启动，虚拟屏核心确定未拉起，按未发送报告。
    if (isBusy(output))
      return com.deepseekharness.app.device.DeviceTransportResult.notSent(
          "root", "ROOT_BUSY", output);
    return com.deepseekharness.app.device.DeviceTransportResult.fromLegacy(
        "root", output, com.deepseekharness.app.device.DeviceTransportIdentity.unknown());
  }

  /**
   * Typed external-device execution. This is deliberately separate from exec(): it accepts an
   * argv array only, never a shell string, and is callable only through the dedicated root helper.
   */
  public static String execExternal(
      Context ctx,
      java.util.List<String> argv,
      java.util.Map<String, String> environment,
      long timeoutMs) {
    return execExternal(ctx, argv, environment, timeoutMs, null, () -> false);
  }

  public static String execExternal(
      Context ctx,
      java.util.List<String> argv,
      java.util.Map<String, String> environment,
      long timeoutMs,
      String expectedRoot,
      java.util.function.BooleanSupplier dispatchCurrent) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    if (executable() == null)
      return com.deepseekharness.app.util.UiText.text("[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]");
    if (argv == null || argv.isEmpty() || argv.size() > 32 || timeoutMs <= 0 || timeoutMs > 900_000)
      return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 外接命令参数无效\n[EXIT=126]");
    String externalPolicy =
        expectedRoot == null
            ? "EXTERNAL_ROOT_REQUIRED"
            : com.deepseekharness.app.util.ExternalToolPolicy.rejection(argv, expectedRoot);
    if (externalPolicy != null) return "[POLICY_BLOCKED] " + externalPolicy + "\n[EXIT=126]";
    try {
      org.json.JSONArray values = new org.json.JSONArray();
      for (String value : argv) {
        if (value == null || value.isEmpty() || value.length() > 4096 || value.indexOf('\0') >= 0)
          return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 外接命令参数无效\n[EXIT=126]");
        values.put(value);
      }
      org.json.JSONObject env = new org.json.JSONObject();
      if (environment != null) {
        if (environment.size() > 32) return "[POLICY_BLOCKED] 外接环境变量过多\n[EXIT=126]";
        for (var entry : environment.entrySet()) {
          String key = entry.getKey(), value = entry.getValue();
          if (key == null
              || !key.matches("[A-Z_][A-Z0-9_]{0,63}")
              || value == null
              || value.length() > 4096
              || value.indexOf('\0') >= 0) return "[POLICY_BLOCKED] 外接环境变量无效\n[EXIT=126]";
          env.put(key, value);
        }
      }
      org.json.JSONObject request =
          new org.json.JSONObject()
              .put("operation", "external-argv")
              .put("argv", values)
              .put("environment", env)
              .put("timeoutMs", timeoutMs)
              .put("expectedRoot", expectedRoot)
              .put("selfPackage", ctx.getPackageName())
              .put("selfUid", ctx.getApplicationInfo().uid);
      return runPrivilegedExternal(ctx, request, timeoutMs, dispatchCurrent);
    } catch (Exception error) {
      return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
    }
  }

  /** Runs one owned local-only ADB server and client inside the same root helper process. */
  public static String execExternalAdbSession(
      Context ctx,
      java.util.List<String> serverArgv,
      java.util.List<String> clientArgv,
      java.util.Map<String, String> environment,
      long timeoutMs,
      String expectedRoot,
      java.util.function.BooleanSupplier dispatchCurrent) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    if (executable() == null)
      return com.deepseekharness.app.util.UiText.text("[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]");
    if (timeoutMs <= 0 || timeoutMs > 900_000 || expectedRoot == null)
      return "[POLICY_BLOCKED] 外接 ADB 会话参数无效\n[EXIT=126]";
    String serverPolicy =
        com.deepseekharness.app.util.ExternalToolPolicy.rejectionAdbServer(
            serverArgv, expectedRoot);
    String clientPolicy =
        com.deepseekharness.app.util.ExternalToolPolicy.rejection(clientArgv, expectedRoot);
    if (serverPolicy != null || clientPolicy != null)
      return "[POLICY_BLOCKED] "
          + (serverPolicy != null ? serverPolicy : clientPolicy)
          + "\n[EXIT=126]";
    try {
      org.json.JSONArray serverValues = new org.json.JSONArray();
      org.json.JSONArray clientValues = new org.json.JSONArray();
      for (String value : serverArgv) serverValues.put(value);
      for (String value : clientArgv) clientValues.put(value);
      org.json.JSONObject env = new org.json.JSONObject();
      if (environment == null || environment.size() > 32)
        return "[POLICY_BLOCKED] 外接环境变量无效\n[EXIT=126]";
      for (var entry : environment.entrySet()) {
        String key = entry.getKey(), value = entry.getValue();
        if (key == null
            || !key.matches("[A-Z_][A-Z0-9_]{0,63}")
            || value == null
            || value.length() > 4096
            || value.indexOf('\0') >= 0) return "[POLICY_BLOCKED] 外接环境变量无效\n[EXIT=126]";
        env.put(key, value);
      }
      org.json.JSONObject request =
          new org.json.JSONObject()
              .put("operation", "external-adb-session")
              .put("serverArgv", serverValues)
              .put("argv", clientValues)
              .put("environment", env)
              .put("timeoutMs", timeoutMs)
              .put("expectedRoot", expectedRoot)
              .put("selfPackage", ctx.getPackageName())
              .put("selfUid", ctx.getApplicationInfo().uid);
      // server 就绪等待最多 15 秒，再加客户端期限与 su 启动余量。
      return runPrivilegedExternal(ctx, request, timeoutMs + 25_000, dispatchCurrent);
    } catch (Exception error) {
      return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
    }
  }

  /**
   * 同一专属 server 内顺序执行多步客户端命令；每步都在 App 侧和 root helper 内各按 {@link
   * com.deepseekharness.app.util.ExternalToolPolicy} 核验一次。输出格式见 {@link
   * com.deepseekharness.app.util.ExternalStepOutput}。
   */
  public static String execExternalAdbSteps(
      Context ctx,
      java.util.List<String> serverArgv,
      java.util.List<java.util.List<String>> steps,
      java.util.Map<String, String> environment,
      long timeoutMs,
      String expectedRoot,
      String importRoot,
      String nonce,
      boolean continueOnFailure,
      java.util.function.BooleanSupplier dispatchCurrent) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    if (executable() == null)
      return com.deepseekharness.app.util.UiText.text("[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]");
    if (timeoutMs <= 0
        || timeoutMs > 900_000
        || expectedRoot == null
        || steps == null
        || steps.isEmpty()
        || steps.size() > 64
        || !com.deepseekharness.app.util.ExternalStepOutput.validNonce(nonce))
      return "[POLICY_BLOCKED] 外接 ADB 会话参数无效\n[EXIT=126]";
    String serverPolicy =
        com.deepseekharness.app.util.ExternalToolPolicy.rejectionAdbServer(
            serverArgv, expectedRoot);
    if (serverPolicy != null) return "[POLICY_BLOCKED] " + serverPolicy + "\n[EXIT=126]";
    try {
      org.json.JSONArray stepValues = new org.json.JSONArray();
      for (int i = 0; i < steps.size(); i++) {
        String policy =
            com.deepseekharness.app.util.ExternalToolPolicy.rejection(
                steps.get(i), expectedRoot, importRoot);
        if (policy != null) return "[POLICY_BLOCKED] " + policy + " @step " + i + "\n[EXIT=126]";
        stepValues.put(new org.json.JSONArray(steps.get(i)));
      }
      org.json.JSONObject env = new org.json.JSONObject();
      if (environment == null || environment.size() > 32)
        return "[POLICY_BLOCKED] 外接环境变量无效\n[EXIT=126]";
      for (var entry : environment.entrySet()) {
        String key = entry.getKey(), value = entry.getValue();
        if (key == null
            || !key.matches("[A-Z_][A-Z0-9_]{0,63}")
            || value == null
            || value.length() > 4096
            || value.indexOf('\0') >= 0) return "[POLICY_BLOCKED] 外接环境变量无效\n[EXIT=126]";
        env.put(key, value);
      }
      org.json.JSONObject request =
          new org.json.JSONObject()
              .put("operation", "external-adb-steps")
              .put("serverArgv", new org.json.JSONArray(serverArgv))
              .put("steps", stepValues)
              .put("environment", env)
              .put("timeoutMs", timeoutMs)
              .put("expectedRoot", expectedRoot)
              .put("importRoot", importRoot == null ? "" : importRoot)
              .put("nonce", nonce)
              .put("continueOnFailure", continueOnFailure)
              .put("selfPackage", ctx.getPackageName())
              .put("selfUid", ctx.getApplicationInfo().uid);
      return runPrivilegedExternal(ctx, request, timeoutMs + 25_000, dispatchCurrent);
    } catch (Exception error) {
      return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
    }
  }

  private static String runPrivilegedExternal(
      Context ctx,
      org.json.JSONObject request,
      long timeoutMs,
      java.util.function.BooleanSupplier dispatchCurrent) {
    if (!dispatchCurrent.getAsBoolean()) return externalAuthorityChanged();
    String su = executable();
    if (su == null) return "[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]";
    String label = request.optString("operation", "external");
    com.deepseekharness.app.util.RootHelperSlots.Ticket<Process> ticket =
        SLOTS.tryAcquire(label, now());
    if (ticket == null) return busy(ctx, label);
    Process process = null;
    boolean timedOut = false;
    try {
      bindOwner(request, timeoutMs);
      byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
      process = startHelper(ctx, su, ticket);
      try (java.io.OutputStream input = process.getOutputStream()) {
        // A helper cannot execute an external command without its request. Claim immediately
        // before handing over the immutable argv, after potentially slow su startup and checks.
        if (!writeExternalRequest(
            input, payload, () -> enabled(ctx) && dispatchCurrent.getAsBoolean()))
          return externalAuthorityChanged();
      }
      BoundedProcessRunner.Result result =
          BoundedProcessRunner.collect(process, timeoutMs, 768 * 1024, RootShell::destroyQuietly);
      timedOut = result.timedOut;
      int marker = result.output.lastIndexOf(RootShellMain.RESULT);
      if (!result.timedOut && !result.truncated && marker >= 0) {
        String encoded = result.output.substring(marker + RootShellMain.RESULT.length()).trim();
        return new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
      }
      return "[EXECUTION_UNKNOWN] 外接 root 进程未返回完整结果，不会自动重试\n[EXIT=125]";
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return "[EXECUTION_UNKNOWN] 外接 root 请求已中断，不会自动重试\n[EXIT=125]";
    } catch (Throwable error) {
      return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
    } finally {
      finish(ctx, ticket, process, label, timedOut);
    }
  }

  private static String externalAuthorityChanged() {
    return "[POLICY_BLOCKED] EXTERNAL_AUTHORITY_CHANGED_BEFORE_DISPATCH\n[EXIT=126]";
  }

  static boolean writeExternalRequest(
      java.io.OutputStream input,
      byte[] payload,
      java.util.function.BooleanSupplier dispatchCurrent)
      throws java.io.IOException {
    if (!dispatchCurrent.getAsBoolean()) return false;
    input.write(payload);
    return true;
  }

  /**
   * root helper 同时最多 3 个。T38 真机证据：App 的 uid 对 su 提权后的 uid 0 helper 发信号得到 EPERM，
   * 超时后结束不了它；槽位按进程真正退出释放，满了就在启动 su 前拒绝，避免 root 进程越积越多。
   */
  private static final int ROOT_HELPER_CAPACITY = 3;

  private static final com.deepseekharness.app.util.RootHelperSlots<Process> SLOTS =
      new com.deepseekharness.app.util.RootHelperSlots<>(
          ROOT_HELPER_CAPACITY, process -> !ProcessTermination.exited(process));

  private static long now() {
    return android.os.SystemClock.elapsedRealtime();
  }

  /** 诊断用：当前 root helper 槽位占用。 */
  public static String slotSnapshot() {
    return SLOTS.snapshot(now()).render();
  }

  /** 诊断用：最近一次 root 结果，不含命令内容。 */
  public static String lastResultSummary() {
    LastResult last = lastResult;
    return last.status + (last.detail.isEmpty() ? "" : " " + last.detail);
  }

  /** 是否为「槽位已满、命令未发送」的结果；调用方可据此报告未发送。 */
  public static boolean isBusy(String output) {
    return output != null && output.startsWith(BUSY_MARKER);
  }

  private static final String BUSY_MARKER = "[ROOT_BUSY]";

  private static String busy(Context ctx, String label) {
    com.deepseekharness.app.util.RootHelperSlots.Snapshot slots = SLOTS.snapshot(now());
    com.deepseekharness.app.device.DeviceChannelDiagnostics.record(ctx, "root-busy:" + label);
    return BUSY_MARKER
        + " root 通道已有 "
        + (slots.active + slots.lingering)
        + " 个进程未结束（其中 "
        + slots.lingering
        + " 个已超时仍在运行），本次命令未发送，可稍后重试；"
        + "长时间不恢复请在 root 管理器中结束 DSHA 的 root 进程或重启 DSHA。\n[EXIT=124]";
  }

  /** 交给 helper 的自退依据：发起方进程身份与截止时长（见 RootHelperWatchdog）。 */
  private static void bindOwner(org.json.JSONObject request, long waitMs)
      throws org.json.JSONException {
    int pid = android.os.Process.myPid();
    long started = -1;
    try {
      com.deepseekharness.app.util.ProcStat stat =
          com.deepseekharness.app.util.ProcStat.parse(
              new String(
                      Compat.readAllBytes(new File("/proc/self/stat")), StandardCharsets.US_ASCII)
                  .trim(),
              pid);
      if (stat != null) started = stat.started;
    } catch (Throwable ignored) {
    }
    request
        .put("ownerPid", pid)
        .put("ownerStarted", started)
        .put(
            "helperDeadlineMs",
            Math.min(
                waitMs + com.deepseekharness.app.util.RootHelperWatchdog.EXIT_MARGIN_MS,
                com.deepseekharness.app.util.RootHelperWatchdog.MAX_DEADLINE_MS));
  }

  private static Process startHelper(
      Context ctx, String su, com.deepseekharness.app.util.RootHelperSlots.Ticket<Process> ticket)
      throws java.io.IOException {
    String entry =
        "CLASSPATH="
            + ShellQuote.arg(ctx.getApplicationInfo().sourceDir)
            + " /system/bin/app_process /system/bin "
            + RootShellMain.class.getName();
    Process process = new ProcessBuilder(su, "-c", entry).redirectErrorStream(true).start();
    SLOTS.attach(ticket, process);
    return process;
  }

  /** 结束失败（root 进程拒绝 App 的信号）不能覆盖已经得到的结果，也不能让调用线程抛出。 */
  private static void destroyQuietly(Process process) {
    try {
      Compat.destroy(process);
    } catch (RuntimeException denied) {
      // 仍未退出的 helper 由槽位登记为滞留，并由 helper 自身的看门狗在截止后退出。
    }
  }

  private static void finish(
      Context ctx,
      com.deepseekharness.app.util.RootHelperSlots.Ticket<Process> ticket,
      Process process,
      String label,
      boolean timedOut) {
    if (process != null) {
      if (!ProcessTermination.exited(process)) destroyQuietly(process);
      closeQuietly(process.getOutputStream());
      closeQuietly(process.getInputStream());
      closeQuietly(process.getErrorStream());
    }
    boolean lingering = SLOTS.release(ticket, now());
    if (lingering)
      com.deepseekharness.app.device.DeviceChannelDiagnostics.record(
          ctx, "root-lingering:" + label);
    else if (timedOut)
      com.deepseekharness.app.device.DeviceChannelDiagnostics.record(ctx, "root-timeout:" + label);
  }

  private static void closeQuietly(java.io.Closeable stream) {
    try {
      stream.close();
    } catch (java.io.IOException ignored) {
    }
  }

  private static String runPrivileged(
      Context ctx, String command, int authorizedSmsUser, String operation) {
    String su = executable();
    if (su == null)
      return com.deepseekharness.app.util.UiText.text("[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]");
    String label = operation.isEmpty() ? "exec" : operation;
    com.deepseekharness.app.util.RootHelperSlots.Ticket<Process> ticket =
        SLOTS.tryAcquire(label, now());
    if (ticket == null) return busy(ctx, label);
    Process process = null;
    boolean timedOut = false;
    try {
      // 命令通过标准输入传递；su 的 shell 参数只含固定入口与已安装 APK 路径。
      org.json.JSONObject request =
          new org.json.JSONObject()
              .put("command", command)
              .put("smsUser", authorizedSmsUser)
              .put("selfPackage", ctx.getPackageName())
              .put("selfUid", ctx.getApplicationInfo().uid);
      if (!operation.isEmpty()) request.put("operation", operation);
      bindOwner(request, ROOT_WAIT_MS);
      process = startHelper(ctx, su, ticket);
      try (java.io.OutputStream input = process.getOutputStream()) {
        input.write(request.toString().getBytes(StandardCharsets.UTF_8));
      }
      BoundedProcessRunner.Result result =
          BoundedProcessRunner.collect(process, ROOT_WAIT_MS, 400_000, RootShell::destroyQuietly);
      timedOut = result.timedOut;
      int marker = result.output.lastIndexOf(RootShellMain.RESULT);
      if (!result.timedOut && !result.truncated && marker >= 0) {
        String encoded = result.output.substring(marker + RootShellMain.RESULT.length()).trim();
        String output = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
        lastResult = new LastResult(Status.RESPONDED, su, "");
        return output;
      }
      lastResult =
          new LastResult(
              result.timedOut ? Status.TIMED_OUT : Status.FAILED,
              su,
              "exit="
                  + result.exitCode
                  + " bytes="
                  + result.totalBytes
                  + " truncated="
                  + result.truncated);
      return "[EXECUTION_UNKNOWN] "
          + lastResult.render(su)
          + com.deepseekharness.app.util.UiText.text("。未取得完整结果，不会自动重试\n[EXIT=125]");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return com.deepseekharness.app.util.UiText.text(
          "[EXECUTION_UNKNOWN] root 请求已中断，不会自动重试\n[EXIT=125]");
    } catch (Throwable e) {
      lastResult = new LastResult(Status.FAILED, su, SensitiveData.redact(String.valueOf(e)));
      return "[EXECUTION_UNKNOWN] " + lastResult.render(su) + "\n[EXIT=125]";
    } finally {
      finish(ctx, ticket, process, label, timedOut);
    }
  }

  /** 普通 root 命令的 App 侧等待；helper 内部单条命令 30 秒，余量给 su 授权与 JVM 启动。 */
  private static final long ROOT_WAIT_MS = 65_000L;
}
