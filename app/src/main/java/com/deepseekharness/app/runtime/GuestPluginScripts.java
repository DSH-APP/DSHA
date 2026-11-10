package com.deepseekharness.app.runtime;

import android.content.Context;
import android.util.Log;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.GuestTimingEvidence;
import com.deepseekharness.app.util.PluginDownloadSource;
import com.deepseekharness.app.util.RegisterBudget;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 通过固定 proot 入口准备并运行插件与 profile 脚本。 */
final class GuestPluginScripts {
  static final String BUILTIN_REGISTER_SCRIPT = "register-builtin-plugins.py";
  static final String RC1_MIGRATION_SCRIPT = "rc1-migration.py";
  static final String PLUGIN_MANAGER_SCRIPT = "plugin-manager.py";

  /** 上一次成功注册写下的受管资产身份；只用于选择注册预算，不参与完成判定。 */
  static final String REGISTER_IDENTITY = "usr/local/share/dsha/register-managed-asset-id";

  private static final GuestScriptQueue SCRIPT_QUEUE = new GuestScriptQueue(64);
  static final long MANAGER_TIMEOUT_MS = 3_000_000;
  static final long QUERY_TIMEOUT_MS = GuestScriptBudget.QUERY_TIMEOUT_MS;

  interface Runner {
    BoundedProcessRunner.Result run(String command, long timeoutMs)
        throws IOException, InterruptedException;

    /** 默认忽略进度行；真实 proot runner 覆写它，把 guest 的 REGISTER_TIMING 带进诊断。 */
    default BoundedProcessRunner.Result run(String command, long timeoutMs, Consumer<String> onLine)
        throws IOException, InterruptedException {
      return run(command, timeoutMs);
    }
  }

  interface Preparation {
    boolean environmentReady();

    boolean pythonReady();

    void pnpm();

    void assets() throws IOException;

    boolean rootPresent();

    default String reusableGeneration() throws IOException {
      return null;
    }

    /** Query-only readiness; must not write, install, repair, migrate or register anything. */
    default boolean queryReady() {
      return false;
    }

    default boolean cancelled(String taskId) {
      return false;
    }

    /** 当前 APK 的受管资产身份；null 表示未知，注册按首启预算处理。 */
    default String managedAssetId() {
      return null;
    }

    /** 上一次成功注册绑定的受管资产身份；null 表示从未成功或未知。 */
    default String lastRegisteredAssetId() {
      return null;
    }
  }

  private final Preparation preparation;
  private final Runner runner;
  private final java.util.LinkedHashMap<String, String> preparedReuse =
      new java.util.LinkedHashMap<>();

  GuestPluginScripts(Context context, File root, BooleanSupplier ready, Runner runner) {
    this(
        new Preparation() {
          public boolean environmentReady() {
            return ready.getAsBoolean();
          }

          public boolean rootPresent() {
            return root.isDirectory();
          }

          public boolean pythonReady() {
            try {
              RootfsInstaller.python(context, root);
              return true;
            } catch (IOException | RuntimeException error) {
              Log.w(
                  "DSHA",
                  UiText.format(
                      "Ubuntu Python 安装失败: %s", SensitiveData.redact(String.valueOf(error))));
              return false;
            }
          }

          public void pnpm() {
            try {
              RootfsInstaller.pnpm(context, root);
            } catch (IOException | RuntimeException error) {
              Log.w(
                  "DSHA",
                  UiText.format("离线 pnpm 安装失败: %s", SensitiveData.redact(String.valueOf(error))));
            }
          }

          public void assets() throws IOException {
            RuntimeTools.prepare(context, root);
          }

          public String reusableGeneration() throws IOException {
            var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
            var host =
                com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
                    fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
            File current =
                new com.deepseekharness.app.backup.UserDataLayout(fs, host.files).current();
            String generation = Rc1MigrationCache.generation(fs, host.files, current);
            host.verify(fs);
            return generation;
          }

          public boolean queryReady() {
            return RuntimeTools.queryReady(context, root);
          }

          public boolean cancelled(String taskId) {
            return taskId != null
                && taskId.matches("[a-f0-9]{32}")
                && new File(root, "root/.dsha-plugin-task-" + taskId + ".cancel").exists();
          }

          public String managedAssetId() {
            return RuntimeTools.managedAssetId(context);
          }

          public String lastRegisteredAssetId() {
            return RuntimeTools.lastRegisteredAssetId(context, root);
          }
        },
        runner);
  }

  GuestPluginScripts(Preparation preparation, Runner runner) {
    this.preparation = java.util.Objects.requireNonNull(preparation);
    this.runner = java.util.Objects.requireNonNull(runner);
  }

  /** One preparation for the two ordered startup helpers, without caching mutable root proof. */
  Startup startup() throws IOException, InterruptedException {
    GuestScriptQueue.Lease lease =
        SCRIPT_QUEUE.enter(
            new GuestScriptBudget(35_000, () -> false, "PLUGIN_STARTUP_QUEUE"), true);
    try {
      preparePlugins(null);
      return new Startup(lease);
    } catch (IOException | InterruptedException | RuntimeException | Error failure) {
      lease.close();
      throw failure;
    }
  }

  final class Startup implements AutoCloseable {
    private final GuestScriptQueue.Lease lease;
    private final Thread owner = Thread.currentThread();
    private boolean registerAttempted, registered, migrated, closed;

    private Startup(GuestScriptQueue.Lease lease) {
      this.lease = lease;
    }

    private void requireOwner() throws IOException, InterruptedException {
      if (closed || Thread.currentThread() != owner)
        throw new IOException("STARTUP_PLUGIN_PREPARATION_OWNER");
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedException("STARTUP_PLUGIN_PREPARATION_INTERRUPTED");
    }

    BoundedProcessRunner.Result register() throws IOException, InterruptedException {
      requireOwner();
      if (registerAttempted || migrated)
        throw new IOException("STARTUP_PLUGIN_REGISTER_ALREADY_ATTEMPTED");
      // Mark before dispatch: UNKNOWN or failed commands never gain an implicit retry here.
      registerAttempted = true;
      BoundedProcessRunner.Result result = runRegister("", true);
      registered = true;
      return result;
    }

    BoundedProcessRunner.Result legacy() throws IOException, InterruptedException {
      requireOwner();
      if (!registered || migrated) throw new IOException("STARTUP_PLUGIN_MIGRATION_ORDER");
      migrated = true;
      return runner.run(
          "DSHA_PLUGIN_DOWNLOAD_SOURCE=auto python3 /root/.dsh/"
              + PLUGIN_MANAGER_SCRIPT
              + " migrate-review-markers 2>&1",
          MANAGER_TIMEOUT_MS);
    }

    public void close() {
      if (closed) return;
      lease.close();
      closed = true;
    }
  }

  BoundedProcessRunner.Result builtin(String extraArgs) throws IOException, InterruptedException {
    try (GuestScriptQueue.Lease ignored =
        SCRIPT_QUEUE.enter(
            new GuestScriptBudget(35_000, () -> false, "PLUGIN_STARTUP_QUEUE"), true)) {
      preparePlugins();
      if (extraArgs.isEmpty()) return runRegister("", true);
      BoundedProcessRunner.Result result =
          runner.run(registerCommand(extraArgs), registerTimeoutMs());
      if (result != null && result.timedOut) clearStaleProfileLock();
      return result;
    }
  }

  String register() {
    return builtinText("");
  }

  String enable(String name, boolean enabled) {
    if (name == null || name.isEmpty()) return "NO_NAME";
    return builtinText((enabled ? "--enable " : "--disable ") + ShellQuote.arg(name));
  }

  private String builtinText(String args) {
    try {
      return GuestCommandOutput.legacy(builtin(args), registerTimeoutMs());
    } catch (IOException | InterruptedException | RuntimeException error) {
      return failure(error, "内置插件脚本执行失败: %s", "ERROR: ");
    }
  }

  BoundedProcessRunner.Result migration(String command, String startupId)
      throws IOException, InterruptedException {
    if (!preparation.rootPresent())
      throw new GuestPreparationFailure(GuestPreparationFailure.Code.ENV_NOT_READY);
    String reusable = null;
    try {
      reusable = preparation.reusableGeneration();
    } catch (IOException | RuntimeException unavailable) {
      /* 证据未知时仍走真实 guest 校验。 */
    }
    if (reusable != null) {
      if ("prepare".equals(command)) {
        synchronized (preparedReuse) {
          preparedReuse.put(startupId, reusable);
          while (preparedReuse.size() > 8)
            preparedReuse.remove(preparedReuse.keySet().iterator().next());
        }
        return BoundedProcessRunner.localCompletion(Rc1MigrationCache.output(reusable, false));
      }
      if ("finalize".equals(command)) {
        String prepared;
        synchronized (preparedReuse) {
          prepared = preparedReuse.remove(startupId);
        }
        if (reusable.equals(prepared))
          return BoundedProcessRunner.localCompletion(Rc1MigrationCache.output(reusable, true));
      }
    }
    synchronized (preparedReuse) {
      preparedReuse.remove(startupId);
    }
    preparation.assets();
    return runner.run(migrationCommand(command, startupId), 600_000);
  }

  String migrationText(String command, String startupId) {
    try {
      String result = GuestCommandOutput.legacy(migration(command, startupId), 600_000);
      return result == null ? "RC1_MIGRATION_NO_OUTPUT" : result;
    } catch (IOException | InterruptedException | RuntimeException error) {
      return failure(error, "rc1 数据迁移保护失败：%s", "RC1_MIGRATION_ERROR: ");
    }
  }

  static String migrationCommand(String command, String startupId) {
    if (!"prepare".equals(command) && !"finalize".equals(command))
      throw new IllegalArgumentException("RC1_MIGRATION_COMMAND");
    return "python3 -B /root/.dsh/"
        + RC1_MIGRATION_SCRIPT
        + " "
        + command
        + " --root /root --state-root /run/dsha-rc1-state --startup-id "
        + ShellQuote.arg(startupId)
        + " --approved-root /sdcard/Documents/dshdata --approved-root /storage/emulated/0/Documents/dshdata 2>&1";
  }

  BoundedProcessRunner.Result manager(String args, String taskId, PluginDownloadSource source)
      throws IOException, InterruptedException {
    boolean query = "list".equals(args) || "refresh".equals(args);
    boolean intent =
        args != null && (args.startsWith("enable-state ") || args.startsWith("disable-state "));
    return manager(
        args,
        taskId,
        source,
        query || intent ? QUERY_TIMEOUT_MS : MANAGER_TIMEOUT_MS,
        query || intent);
  }

  BoundedProcessRunner.Result manager(
      String args,
      String taskId,
      PluginDownloadSource source,
      long totalMillis,
      boolean boundedPreparation)
      throws IOException, InterruptedException {
    boolean query = "list".equals(args) || "refresh".equals(args);
    java.util.Objects.requireNonNull(source, "downloadSource");
    GuestScriptBudget budget =
        boundedPreparation
            ? new GuestScriptBudget(
                totalMillis,
                () -> preparation.cancelled(taskId),
                "list".equals(args) || "refresh".equals(args) ? "PLUGIN_QUERY" : "PLUGIN_INTENT")
            : null;
    try (var scope = budget == null ? null : budget.enter()) {
      // 查询只做就绪校验；完整准备仅属于启动、变更和迁移路径。
      if (query) {
        try (GuestScriptQueue.Lease ignored = SCRIPT_QUEUE.enter(budget, false)) {
          preparePluginsForQuery(budget);
          return runManager(args, taskId, source, totalMillis, budget, true);
        }
      }
      try (GuestScriptQueue.Lease ignored = SCRIPT_QUEUE.enter(budget, true)) {
        preparePlugins(budget);
        return runManager(args, taskId, source, totalMillis, budget, false);
      }
    }
  }

  private BoundedProcessRunner.Result runManager(
      String args,
      String taskId,
      PluginDownloadSource source,
      long totalMillis,
      GuestScriptBudget budget,
      boolean query)
      throws IOException, InterruptedException {
    String task =
        taskId != null && taskId.matches("[a-f0-9]{32}") ? "DSHA_PLUGIN_TASK=" + taskId + " " : "";
    BoundedProcessRunner.Result result =
        runner.run(
            task
                + (query ? "DSHA_PLUGIN_QUERY=1 " : "")
                + "DSHA_PLUGIN_DOWNLOAD_SOURCE="
                + source.value
                + " python3 /root/.dsh/"
                + PLUGIN_MANAGER_SCRIPT
                + (args == null || args.isEmpty() ? "" : " " + args)
                + " 2>&1",
            budget == null ? totalMillis : budget.remainingMillis());
    if (budget != null) budget.remainingMillis();
    return result;
  }

  String managerText(String args, String taskId) {
    try {
      return GuestCommandOutput.legacy(
          manager(args, taskId, PluginDownloadSource.AUTO), MANAGER_TIMEOUT_MS);
    } catch (IOException | InterruptedException | RuntimeException error) {
      return failure(error, "插件管理脚本执行失败: %s", "ERROR: ");
    }
  }

  private void preparePlugins() throws IOException {
    try {
      preparePlugins(null);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new java.io.InterruptedIOException("PLUGIN_PREPARATION_INTERRUPTED");
    }
  }

  private void preparePluginsForQuery(GuestScriptBudget budget)
      throws IOException, InterruptedException {
    if (budget != null) budget.remainingMillis();
    if (!preparation.environmentReady())
      throw new GuestPreparationFailure(GuestPreparationFailure.Code.ENV_NOT_READY);
    if (budget != null) budget.remainingMillis();
    if (!preparation.queryReady())
      throw new GuestPreparationFailure(GuestPreparationFailure.Code.PREPARATION_REQUIRED);
  }

  private void preparePlugins(GuestScriptBudget budget) throws IOException, InterruptedException {
    if (budget != null) budget.remainingMillis();
    if (!preparation.environmentReady())
      throw new GuestPreparationFailure(GuestPreparationFailure.Code.ENV_NOT_READY);
    if (budget != null) budget.remainingMillis();
    boolean pythonReady = preparation.pythonReady();
    if (budget != null) budget.remainingMillis();
    if (!pythonReady)
      throw new GuestPreparationFailure(GuestPreparationFailure.Code.BUNDLED_PYTHON_UNAVAILABLE);
    if (budget != null) budget.remainingMillis();
    // pnpm 失败不阻断列表、开关和删除；依赖安装单独报告其失败。
    preparation.pnpm();
    if (budget != null) budget.remainingMillis();
    preparation.assets();
    if (budget != null) budget.remainingMillis();
  }

  /** 覆盖升级后首启或上次注册不完整时使用宽预算；常规启动保持 90 秒。 */
  long registerTimeoutMs() {
    return RegisterBudget.timeoutMs(
        preparation.lastRegisteredAssetId(), preparation.managedAssetId());
  }

  private static String registerCommand(String extraArgs) {
    return "python3 -u /root/.dsh/"
        + BUILTIN_REGISTER_SCRIPT
        + (extraArgs.isEmpty() ? "" : " " + extraArgs)
        + " 2>&1";
  }

  /**
   * 启动注册的唯一执行路径：分级预算 + guest 阶段证据 + 超时清锁 + 完成回执。
   *
   * <p>失败消息只追加宿主与 guest 的分段数字，不把 guest 自报时间当成完成、失败或重试依据。
   */
  private BoundedProcessRunner.Result runRegister(String extraArgs, boolean requireReceipt)
      throws IOException, InterruptedException {
    RegisterTiming timing = new RegisterTiming();
    BoundedProcessRunner.Result result =
        runner.run(registerCommand(extraArgs), registerTimeoutMs(), timing);
    if (result == null) throw new IOException("BUILTIN_REGISTER_NO_RESULT");
    if (result.timedOut) clearStaleProfileLock();
    try {
      String output =
          com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
              result, "BUILTIN_REGISTER");
      if (requireReceipt && !output.contains("BUILTIN_REGISTER_OK"))
        throw new IOException("BUILTIN_REGISTER_RECEIPT_MISSING");
      return result;
    } catch (IOException incomplete) {
      throw new IOException(incomplete.getMessage() + " " + timing.evidence(), incomplete);
    }
  }

  /** 宿主杀进程后 guest 的 finally 不会执行；只清理由已退出持有者留下的 profile 锁。 */
  private void clearStaleProfileLock() {
    try {
      BoundedProcessRunner.Result cleared =
          runner.run(
              "python3 /root/.dsh/" + BUILTIN_REGISTER_SCRIPT + " --clear-stale-profile-lock 2>&1",
              10_000);
      if (cleared != null && cleared.output.contains("PROFILE_LOCK_CLEARED"))
        Log.i("DSHA", "PROFILE_LOCK_CLEARED after register timeout");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException ignored) {
      // 清理失败不改变超时结论；恢复页列表会提示待确认的锁。
    }
  }

  /** 上一次成功注册写下的受管资产身份；只读 rootfs 内的固定标记。 */
  static String readRegisterIdentity(File root) {
    if (root == null) return null;
    File marker = new File(root, REGISTER_IDENTITY);
    try {
      if (!marker.isFile() || com.deepseekharness.app.util.Compat.isSymbolicLink(marker))
        return null;
      byte[] bytes = java.nio.file.Files.readAllBytes(marker.toPath());
      if (bytes.length > 128) return null;
      return RegisterBudget.identity(new String(bytes, java.nio.charset.StandardCharsets.US_ASCII));
    } catch (IOException | RuntimeException unavailable) {
      return null;
    }
  }

  /** 只收集应用自己的 REGISTER_TIMING 行；数量有界，最后一行用于失败诊断。 */
  private static final class RegisterTiming implements Consumer<String> {
    private static final int LIMIT = 24;
    private final long started = System.nanoTime();
    private final List<GuestTimingEvidence.Phase> phases = new ArrayList<>();

    public void accept(String line) {
      GuestTimingEvidence.Phase phase = GuestTimingEvidence.parse(line);
      if (phase == null) return;
      if (phases.size() >= LIMIT) phases.remove(0);
      phases.add(phase);
    }

    String evidence() {
      long elapsed = Math.max(0, System.nanoTime() - started) / 1_000_000;
      return GuestTimingEvidence.evidence(
          elapsed, phases.isEmpty() ? null : phases.get(phases.size() - 1));
    }
  }

  private static String failure(Exception error, String context, String prefix) {
    if (GuestPreparationFailure.is(error, GuestPreparationFailure.Code.ENV_NOT_READY))
      return "ENV_NOT_READY";
    if (GuestPreparationFailure.is(error, GuestPreparationFailure.Code.BUNDLED_PYTHON_UNAVAILABLE))
      return UiText.text("ERROR: Ubuntu Python 环境未就绪");
    if (GuestPreparationFailure.is(error, GuestPreparationFailure.Code.PREPARATION_REQUIRED))
      return "ENV_PREPARATION_REQUIRED";
    if (error instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      return UiText.text("ERROR: 命令等待被中断");
    }
    String detail = SensitiveData.redact(String.valueOf(error));
    Log.w("DSHA", UiText.format(context, detail));
    return prefix + detail;
  }
}
