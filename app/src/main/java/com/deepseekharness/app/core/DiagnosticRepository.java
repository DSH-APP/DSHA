package com.deepseekharness.app.core;

import android.app.Application;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import com.deepseekharness.app.util.DiagnosticReport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 诊断使用有限的环境探针与结构化操作记录，不读取对话、API 配置或整段 logcat。 */
public final class DiagnosticRepository extends AndroidViewModel {
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  public final MutableLiveData<String> report = new MutableLiveData<>("");
  public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);

  public enum Phase {
    READY,
    RUNNING,
    SUCCEEDED,
    FAILED
  }

  public final MutableLiveData<Phase> phase = new MutableLiveData<>(Phase.READY);

  public static final class Result {
    public final String key, title, status, detail;
    public final DiagnosticReport.Health health;

    Result(DiagnosticReport.Check check) {
      key = check.key;
      health = check.health;
      title = check.title;
      status = check.status;
      detail = check.detail;
    }
  }

  public final MutableLiveData<java.util.List<Result>> results =
      new MutableLiveData<>(java.util.List.of());

  public DiagnosticRepository(@NonNull Application app) {
    super(app);
  }

  public void generate() {
    run(false);
  }

  public void repairNetworkTools() {
    run(true);
  }

  private void run(boolean repair) {
    if (Boolean.TRUE.equals(busy.getValue())) return;
    HarnessController controller = HarnessController.get(getApplication());
    if (com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller)) {
      publishFailure(com.deepseekharness.app.util.UiText.text("上次环境维护未完成，请先到安装与修复页恢复中断维护。"));
      return;
    }
    com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
        com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(
            repair
                ? com.deepseekharness.app.util.UiText.text("诊断修复")
                : com.deepseekharness.app.util.UiText.text("环境诊断"));
    if (lease == null) {
      publishFailure(
          com.deepseekharness.app.util.UiText.format(
              "正在%s，完成后可重新生成报告。",
              com.deepseekharness.app.util.UiStateText.render(
                  com.deepseekharness.app.util.EnvironmentTaskGate.activeKind())));
      return;
    }
    results.setValue(java.util.List.of());
    phase.setValue(Phase.RUNNING);
    busy.setValue(true);
    report.setValue(
        repair
            ? com.deepseekharness.app.util.UiText.text("正在准备证书与网络工具修复…\n")
            : com.deepseekharness.app.util.UiText.text("正在读取设备与环境信息…\n"));
    try {
      IO.execute(
          () -> {
            try (lease) {
              lease.run(
                  () -> {
                    String repairResult = "";
                    boolean repairFailed = false;
                    if (repair) {
                      try {
                        ProotBootstrap proot = HarnessController.get(getApplication()).proot();
                        if (!proot.isEnvironmentReady())
                          throw new java.io.IOException(
                              com.deepseekharness.app.util.UiText.text("环境未就绪，请先完成首次解压"));
                        proot.prepareRuntimeTools();
                        proot.ensureRuntimeFiles();
                        if (!proot.ensureGlibcPython() || !proot.ensureBundledPnpm())
                          throw new java.io.IOException(
                              com.deepseekharness.app.util.UiText.text("内置 Python / pnpm 修复失败"));
                        String output =
                            com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
                                proot.execAndReadWithProotResult(
                                    "python3 -c 'import ssl; ssl.create_default_context()' && npm --version && printf '\\nDSHA_NETWORK_REPAIR_OK\\n'",
                                    30000),
                                "NETWORK_REPAIR");
                        if (!output.contains("DSHA_NETWORK_REPAIR_OK"))
                          throw new java.io.IOException(output);
                        repairResult =
                            com.deepseekharness.app.util.UiText.text(
                                "证书、Python、npm 与 pnpm 已修复并通过启动检查。\n");
                      } catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        repairFailed = true;
                        repairResult =
                            com.deepseekharness.app.util.UiText.format(
                                "修复失败：%s\n", SensitiveData.redact(String.valueOf(e.getMessage())));
                      }
                      DiagnosticLog.record(getApplication(), "REPAIR_NETWORK_TOOLS", repairResult);
                    }
                    DiagnosticReport result;
                    try {
                      result = collect();
                      if (repair)
                        result =
                            result.prepend(
                                new DiagnosticReport.Check(
                                    "network-repair",
                                    repairFailed
                                        ? DiagnosticReport.Health.NEEDS_ATTENTION
                                        : DiagnosticReport.Health.PASS,
                                    t("证书与网络工具修复", "Certificate and network-tool repair"),
                                    repairFailed
                                        ? t("修复失败", "Repair failed")
                                        : t("启动检查通过", "Startup checks passed"),
                                    repairResult));
                    } catch (Exception e) {
                      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                      publishFailure(
                          com.deepseekharness.app.util.UiText.format(
                              "诊断未完成：%s", SensitiveData.redact(String.valueOf(e.getMessage()))));
                      return null;
                    }
                    publish(result);
                    phase.postValue(repairFailed ? Phase.FAILED : Phase.SUCCEEDED);
                    return null;
                  });
            } catch (Exception | LinkageError error) {
              publishFailure(
                  com.deepseekharness.app.util.UiText.format(
                      "诊断未完成：%s", SensitiveData.redact(String.valueOf(error))));
            } finally {
              busy.postValue(false);
            }
          });
    } catch (RuntimeException error) {
      lease.close();
      busy.setValue(false);
      publishFailure(
          com.deepseekharness.app.util.UiText.format(
              "无法开始诊断：%s", SensitiveData.redact(String.valueOf(error))));
    }
  }

  private void publishFailure(String detail) {
    String safe = SensitiveData.redact(detail == null ? "" : detail);
    publish(
        new DiagnosticReport(
            t("DSHA 诊断报告\n", "DSHA diagnostic report\n"),
            List.of(
                new DiagnosticReport.Check(
                    "collection",
                    DiagnosticReport.Health.UNVERIFIED,
                    t("环境诊断", "Environment diagnostics"),
                    t("未完成", "Incomplete"),
                    safe)),
            ""));
    phase.postValue(Phase.FAILED);
  }

  private void publish(DiagnosticReport snapshot) {
    report.postValue(SensitiveData.redact(snapshot.render()));
    List<Result> cards = new ArrayList<>();
    for (DiagnosticReport.Check check : snapshot.checks)
      cards.add(
          new Result(
              new DiagnosticReport.Check(
                  check.key,
                  check.health,
                  check.title,
                  check.status,
                  SensitiveData.redact(check.detail))));
    results.postValue(List.copyOf(cards));
  }

  private DiagnosticReport collect() throws Exception {
    List<DiagnosticReport.Check> rows = new ArrayList<>();
    HarnessController controller = HarnessController.get(getApplication());
    ProotBootstrap proot = controller.proot();
    ConfigStore config = new ConfigStore(getApplication());
    StringBuilder metadata = new StringBuilder();
    metadata
        .append(t("连续 Web 失败：", "Consecutive Web failures: "))
        .append(config.getWebFailures())
        .append("/3\n")
        .append(t("最近失败阶段：", "Latest failure stage: "))
        .append(com.deepseekharness.app.util.UiStateText.render(config.getDiagnosticFailureStage()))
        .append('\n')
        .append(t("最近失败原因：", "Latest failure reason: "))
        .append(config.getDiagnosticFailureReason())
        .append('\n');
    metadata
        .append(t("版本：", "Version: "))
        .append(BuildConfig.VERSION_NAME)
        .append(" / ")
        .append(BuildConfig.VERSION_CODE)
        .append(
            BuildConfig.LOW_ANDROID
                ? t(" / 兼容版\n", " / Low edition\n")
                : t(" / 标准版\n", " / Standard edition\n"));
    metadata
        .append(t("系统：Android ", "System: Android "))
        .append(Build.VERSION.RELEASE)
        .append(" / API ")
        .append(Build.VERSION.SDK_INT)
        .append('\n');
    metadata
        .append(t("机型：", "Device: "))
        .append(Build.MANUFACTURER)
        .append(' ')
        .append(Build.MODEL)
        .append('\n');
    metadata
        .append(t("架构：", "Architectures: "))
        .append(String.join(", ", Build.SUPPORTED_ABIS))
        .append('\n');
    metadata
        .append(t("内核：", "Kernel: "))
        .append(System.getProperty("os.version", t("未知", "Unknown")))
        .append('\n');
    try {
      metadata
          .append(t("内存页：", "Memory page: "))
          .append(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
          .append(" bytes\n");
    } catch (Exception ignored) {
    }
    add(
        rows,
        "device",
        DiagnosticReport.Health.INFO,
        t("应用与设备", "App and device"),
        t("本次只读采样", "Current read-only sample"),
        metadata.toString());
    StringBuilder browser = new StringBuilder();
    boolean browserAvailable = true;
    try {
      android.content.pm.PackageInfo web =
          Build.VERSION.SDK_INT >= 26 ? android.webkit.WebView.getCurrentWebViewPackage() : null;
      browser
          .append("WebView: ")
          .append(
              web == null
                  ? t("系统未提供版本信息", "System did not provide version information")
                  : web.packageName + " " + web.versionName)
          .append('\n');
    } catch (Exception | LinkageError error) {
      browserAvailable = false;
      browser
          .append(t("WebView：不可用（", "WebView: unavailable ("))
          .append(error.getClass().getSimpleName())
          .append(")\n");
    }
    browser
        .append(t("兼容内核：", "Compatibility engine: "))
        .append(
            BuildConfig.LOW_ANDROID
                ? t(
                    "Gecko 143 可用（旧系统自动切换）",
                    "Gecko 143 bundled; selected automatically on older systems")
                : t("未内置", "Not bundled"));
    add(
        rows,
        "browser",
        browserAvailable ? DiagnosticReport.Health.INFO : DiagnosticReport.Health.NEEDS_ATTENTION,
        t("网页内核", "Browser engine"),
        BuildConfig.LOW_ANDROID ? "WebView / Gecko 143" : "System WebView",
        browser.toString());
    String cold = ColdInstallDiagnostics.read(getApplication());
    if (!cold.isEmpty())
      add(
          rows,
          "cold-install",
          DiagnosticReport.Health.INFO,
          t("冷安装诊断记录", "Cold-install diagnostics"),
          t("查看检查结果", "Review results"),
          cold);
    String writeFailure = controller.runtimeHostPorts().diagnosticFailure();
    if (!writeFailure.isEmpty())
      add(
          rows,
          "diagnostic-write",
          DiagnosticReport.Health.NEEDS_ATTENTION,
          t("诊断记录写入", "Diagnostic log writes"),
          t("本次应用进程曾写入失败", "A write failed in this app process"),
          writeFailure
              + "\n\n"
              + t(
                  "失败类型来自本机诊断端口；部分冷安装记录可能未能保存。",
                  "The local diagnostic port recorded this failure type; some cold-install records may not have been saved."));
    boolean ready = proot.isEnvironmentReady();
    add(
        rows,
        "readiness",
        ready ? DiagnosticReport.Health.PASS : DiagnosticReport.Health.NEEDS_ATTENTION,
        t("Ubuntu 与 Bash 加载器", "Ubuntu and Bash loader"),
        ready ? t("就绪检查通过", "Readiness passed") : t("需要处理", "Needs attention"),
        t(
                "就绪检查会核对实际 Bash、ELF 加载器与环境身份。",
                "Readiness checks actual Bash, its ELF loader and environment identity.")
            + "\n\n"
            + (ready
                ? t("当前环境已就绪。", "Environment ready.")
                : t("请到安装与环境查看修复方式。", "Open Installation and environment for repair options.")));
    File root = proot.getRootfsDir();
    String[][] probes = {
      {"Node", "usr/local/bin/node"},
      {"npm", "usr/local/lib/node_modules/npm/bin/npm-cli.js"},
      {t("npm 入口", "npm entry point"), "root/dsh-bin/npm"},
      {t("CA 证书", "CA certificates"), "usr/local/share/dsha/ca-certificates.crt"},
      {t("插件管理器", "Plugin manager"), "root/.dsh/plugin-manager.py"}
    };
    StringBuilder files = new StringBuilder();
    boolean filesPresent = true;
    for (String[] probe : probes) {
      boolean present = new File(root, probe[1]).isFile();
      filesPresent &= present;
      files
          .append(probe[0])
          .append(": ")
          .append(
              present
                  ? t("存在", "Present")
                  : t("缺失，可尝试修复证书与 npm", "Missing; try repairing certificates and npm"))
          .append('\n');
    }
    add(
        rows,
        "runtime-files",
        filesPresent ? DiagnosticReport.Health.PASS : DiagnosticReport.Health.NEEDS_ATTENTION,
        t("运行工具文件", "Runtime tool files"),
        filesPresent ? t("文件存在", "Files present") : t("需要处理", "Needs attention"),
        files.toString());
    report.postValue(
        SensitiveData.redact(
                new DiagnosticReport(t("DSHA 诊断报告\n", "DSHA diagnostic report\n"), rows, "")
                    .render())
            + t(
                "\n正在验证所选运行方式（最多 60 秒）与 Node / npm / Python（最多 20 秒）…\n",
                "\nChecking the selected runtime (up to 60 seconds), then Node / npm / Python (up to 20 seconds)…\n"));
    String selectedMode = "", smokeStatus = "";
    DiagnosticReport.ToolVersions versions = null;
    if (ready) {
      ProotBootstrap.SmokeResult smoke = proot.smokeTestResult();
      selectedMode = smoke.runtimeMode;
      String smokeOutput =
          com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
              smoke.command, "SELECTED_RUNTIME_SMOKE");
      if (!smokeOutput.contains("SMOKE_OK"))
        throw new java.io.IOException("SELECTED_RUNTIME_SMOKE_MARKER");
      smokeStatus = smokeOutput.trim();
      String output =
          com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
              proot.execAndReadWithProotResult(
                  "set -e; node_version=$(node --version); npm_version=$(npm --version); python_version=$(python3 --version); printf 'DSHA_TOOL_NODE=%s\\nDSHA_TOOL_NPM=%s\\nDSHA_TOOL_PYTHON=%s\\n' \"$node_version\" \"$npm_version\" \"$python_version\"",
                  20000),
              "RUNTIME_TOOL_VERSIONS");
      versions = DiagnosticReport.ToolVersions.parse(output);
    }
    add(
        rows,
        "selected-runtime",
        ready ? DiagnosticReport.Health.PASS : DiagnosticReport.Health.UNVERIFIED,
        t("所选运行方式", "Selected runtime"),
        ready ? selectedMode + " · SMOKE_OK" : t("尚未验证", "Not verified"),
        ready
            ? "runtimeMode=" + selectedMode + "\n" + smokeStatus
            : t("运行环境尚未就绪。", "The runtime is not ready."));
    add(
        rows,
        "tool-versions",
        versions == null ? DiagnosticReport.Health.UNVERIFIED : DiagnosticReport.Health.PASS,
        "Node · npm · Python",
        versions == null ? t("尚未验证", "Not verified") : t("实际命令已响应", "Commands responded"),
        versions == null ? t("运行环境尚未就绪。", "The runtime is not ready.") : versions.detail());
    String identity = t("尚未读取到运行时标识。", "Runtime identity unavailable."),
        identityState = t("未知", "Unknown");
    DiagnosticReport.Health identityHealth = DiagnosticReport.Health.UNVERIFIED;
    try {
      var installed = proot.installedRuntimeDescriptor();
      if (installed != null) {
        identity = installed.json().get("dshVersion") + "\n" + installed.id();
        boolean latest = installed.latest(proot.expectedRuntimeDescriptor());
        identityState =
            latest ? t("与包内版本一致", "Matches bundled version") : t("存在版本差异", "Version differs");
        identityHealth =
            latest ? DiagnosticReport.Health.PASS : DiagnosticReport.Health.NEEDS_ATTENTION;
      }
    } catch (Exception unavailable) {
    }
    add(
        rows,
        "runtime-identity",
        identityHealth,
        t("运行时版本与身份", "Runtime version and identity"),
        identityState,
        identity
            + "\n\n"
            + t(
                "完整文件检查请在安装与环境中执行。",
                "Run the complete component check in Installation and environment."));
    String trial = com.deepseekharness.app.runtime.RuntimeTrial.latestFailure(getApplication());
    add(
        rows,
        "runtime-trial",
        trial.isEmpty() ? DiagnosticReport.Health.INFO : DiagnosticReport.Health.NEEDS_ATTENTION,
        t("最近隔离运行试验", "Latest isolated runtime trial"),
        trial.isEmpty()
            ? t("没有失败记录", "No failure recorded")
            : t("失败证据已保留", "Failure evidence retained"),
        trial.isEmpty()
            ? t(
                "运行时更新通过后不会生成失败记录。",
                "A successful runtime update does not create a failure record.")
            : trial);
    File privateDir = getApplication().getFilesDir();
    long usable = privateDir.getUsableSpace();
    boolean privateAvailable = privateDir.isDirectory();
    add(
        rows,
        "storage",
        privateAvailable ? DiagnosticReport.Health.INFO : DiagnosticReport.Health.NEEDS_ATTENTION,
        t("存储与数据目录", "Storage and data folders"),
        privateAvailable
            ? t("私有目录可用", "Private directory available")
            : t("需要处理", "Needs attention"),
        t("可用空间：", "Available space: ")
            + String.format(java.util.Locale.ROOT, "%.2f GiB", usable / 1073741824.0)
            + "\n"
            + t("不遍历对话与个人文件。", "Conversations and personal files are not traversed."));
    boolean web = !controller.getWebAuthUrl().isEmpty();
    String authFailure = controller.getWebAuthFailure();
    add(
        rows,
        "web-auth",
        web ? DiagnosticReport.Health.INFO : DiagnosticReport.Health.UNVERIFIED,
        t("Web 鉴权与桥接", "Web authentication and bridge"),
        web ? t("已有本轮鉴权地址", "Current startup has an auth URL") : t("DSH 未运行", "DSH is not running"),
        t(
                "桥接请求仍须通过鉴权与原生能力确认。",
                "Bridge requests still require authentication and native capability confirmation.")
            + "\n\n"
            + authFailure);
    var trace = controller.startupDiagnostics().snapshot();
    boolean pluginErrors = !trace.issues.isEmpty();
    add(
        rows,
        "plugins",
        pluginErrors ? DiagnosticReport.Health.NEEDS_ATTENTION : DiagnosticReport.Health.INFO,
        t("插件加载", "Plugin loading"),
        pluginErrors
            ? t("存在加载错误", "Loading errors recorded")
            : t("查看启动观察结果", "Review startup observations"),
        pluginErrors
            ? String.join("\n", trace.issues.values())
            : t(
                "本轮没有记录到插件加载错误；未运行的插件仍需使用时验证。",
                "No plugin loading error recorded for this startup. Unused plugins still require runtime verification."));
    add(
        rows,
        "device-channels",
        DiagnosticReport.Health.INFO,
        t("设备通道", "Device channels"),
        t("按需授权", "Optional access"),
        com.deepseekharness.app.RootShell.status(getApplication())
            + "\n\n"
            + com.deepseekharness.app.ShizukuShell.userStatus(getApplication())
            + "\n\nADB: "
            + com.deepseekharness.app.DeviceBridgeService.adbState
            + "\nAccessibility: "
            + com.deepseekharness.app.DshaAccessibilityService.enabledState(getApplication()));
    add(
        rows,
        "host-resources",
        DiagnosticReport.Health.INFO,
        t("宿主进程资源", "Host process resources"),
        t("本次只读采样", "Current read-only sample"),
        hostResources()
            + "\n\n"
            + t(
                "仅当前原生进程；不含 Ubuntu 或独立浏览器子进程。请比较相同操作后的多个样本。",
                "Current native process only; excludes Ubuntu and separate browser child processes. Compare multiple samples after the same operations."));
    add(
        rows,
        "operations",
        DiagnosticReport.Health.INFO,
        t("最近操作与失败步骤", "Recent operations and failed steps"),
        t("查看检查结果", "Review results"),
        DiagnosticLog.read(getApplication()));
    String footer =
        com.deepseekharness.app.util.UiText.text(
            "\n建议操作\n证书或 npm 异常：点击「修复证书与 npm」。\n文件选择无返回：到插件页使用「其他文件选择器」。\n第三方插件导致启动失败：使用启动页的安全启动，再逐个恢复插件。\n存储不足：清理下载目录后重试，避免重新解压整个环境。\n\n隐私范围：未读取 API 配置、对话、终端命令或系统完整日志；没有自动上传此报告。可在下面补充复现步骤后复制或导出。\n");
    return new DiagnosticReport(t("DSHA 诊断报告\n", "DSHA diagnostic report\n"), rows, footer);
  }

  private static void add(
      List<DiagnosticReport.Check> rows,
      String key,
      DiagnosticReport.Health health,
      String title,
      String status,
      String detail) {
    rows.add(new DiagnosticReport.Check(key, health, title, status, detail));
  }

  private static String hostResources() {
    String pss;
    try {
      pss = Long.toString(android.os.Debug.getPss());
    } catch (RuntimeException unavailable) {
      pss = "unavailable";
    }
    return "Host PID: "
        + android.os.Process.myPid()
        + "\nHost PSS KiB: "
        + pss
        + "\nHost FDs: "
        + ownEntryCount("/proc/self/fd")
        + "\nHost threads: "
        + ownEntryCount("/proc/self/task");
  }

  private static String ownEntryCount(String path) {
    try {
      String[] entries = new File(path).list();
      return entries == null ? "unavailable" : Integer.toString(entries.length);
    } catch (SecurityException unavailable) {
      return "unavailable";
    }
  }

  private static String t(String zh, String en) {
    return com.deepseekharness.app.util.UiText.choose(zh, en);
  }
}
