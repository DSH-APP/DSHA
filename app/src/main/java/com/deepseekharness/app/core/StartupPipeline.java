package com.deepseekharness.app.core;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.WebProcessManager;
import java.io.IOException;
import java.util.Map;

/** Preparation only. The controller owns generation, enqueue, process launch and retry. */
final class StartupPipeline {
  interface Actions {
    boolean current();

    void stage(String stage);

    void status(String status);

    void releasePrior() throws IOException;

    void recoverData() throws Exception;

    void cleanup() throws IOException;

    void beforeLaunch(String startupId) throws Exception;

    String safeProfile() throws IOException;

    boolean unlockStartup() throws IOException;

    void checkpoint(String startupId) throws Exception;
  }

  interface Runtime {
    void credentials() throws Exception;

    void stopLan();

    String stopWeb() throws IOException;

    void tools() throws Exception;

    boolean ready();

    boolean offlineBundle();

    void extract() throws Exception;

    String migration(String startupId) throws Exception;

    String register() throws Exception;

    String legacyPlugins() throws Exception;

    com.deepseekharness.app.util.BoundedProcessRunner.Result materializeCasLinks() throws Exception;
  }

  interface Diagnostics {
    String recordId();

    void message(long generation, String message);

    void issue(long generation, String plugin, String message);
  }

  private final Runtime runtime;
  private final Diagnostics diagnostics;

  StartupPipeline(Runtime runtime, Diagnostics diagnostics) {
    this.runtime = runtime;
    this.diagnostics = diagnostics;
  }

  StartupPipeline(
      ConfigStore config,
      ProotBootstrap boot,
      ProotBootstrap stable,
      WebProcessManager webProc,
      StartupDiagnostics diagnostics) {
    this(
        new Runtime() {
          public void credentials() throws Exception {
            config.readApiKey().requireValue();
          }

          public void stopLan() {
            com.deepseekharness.app.LanProxyService.stop();
          }

          public String stopWeb() throws IOException {
            return WebStopText.renderAll(webProc.stopResult().diagnostics());
          }

          public void tools() throws Exception {
            boot.ensureRuntimeFiles();
          }

          public boolean ready() {
            return stable.isEnvironmentReady();
          }

          public boolean offlineBundle() {
            return stable.hasOfflineBundle();
          }

          public void extract() throws Exception {
            stable.extractOfflineBundle((done, total) -> {});
          }

          public String migration(String id) throws Exception {
            return com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
                boot.prepareRc1MigrationResult(id), "RC1_MIGRATION");
          }

          public String register() throws Exception {
            return com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
                boot.registerBuiltinPluginsResult(), "BUILTIN_REGISTER");
          }

          public String legacyPlugins() throws Exception {
            return com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
                boot.runPluginManagerResult("migrate-review-markers", ""),
                "PLUGIN_LEGACY_ACTIVATION");
          }

          public com.deepseekharness.app.util.BoundedProcessRunner.Result materializeCasLinks()
              throws Exception {
            return boot.execAndReadWithProotResult(
                "python3 /root/.dsh/plugin-manager.py materialize-cas-links", 120_000);
          }
        },
        new Diagnostics() {
          public String recordId() {
            return diagnostics.recordId();
          }

          public void message(long g, String s) {
            diagnostics.message(g, s);
          }

          public void issue(long g, String p, String s) {
            diagnostics.issue(g, p, s);
          }
        });
  }

  String prepare(long generation, boolean safeMode, Actions actions) throws Exception {
    if (!actions.current()) return null;
    // The native key is a legacy environment fallback. Its unavailable state remains
    // explicit, but must not prevent opening Web's own credential configuration.
    try {
      runtime.credentials();
    } catch (com.deepseekharness.app.util.CredentialRead.Unavailable unavailable) {
      String warning = legacyCredentialWarning(unavailable.result);
      actions.status(warning);
      diagnostics.message(generation, warning);
    }
    runtime.stopLan();
    actions.stage(com.deepseekharness.app.util.UiText.text("停止旧 Web 进程"));
    String stopError = runtime.stopWeb();
    if (!stopError.isEmpty()) throw new java.io.IOException(stopError);
    actions.releasePrior();
    if (!actions.current()) return null;
    actions.stage(com.deepseekharness.app.util.UiText.text("检查随包运行工具"));
    runtime.tools();
    if (!runtime.ready()) {
      if (!runtime.offlineBundle()) {
        throw new IOException("BUNDLED_ENVIRONMENT_MISSING");
      }
      actions.status(com.deepseekharness.app.util.UiText.text("正在解压内置环境（首次约需几分钟，请勿退出）…"));
      actions.stage(com.deepseekharness.app.util.UiText.text("解压运行环境"));
      runtime.extract();
      actions.status(com.deepseekharness.app.util.UiText.text("环境解压完成，正在启动 dsh web…"));
    }
    if (!actions.current()) return null;
    actions.stage(com.deepseekharness.app.util.UiText.text("恢复数据事务"));
    actions.recoverData();
    try {
      actions.cleanup();
    } catch (IOException cleanup) {
      actions.status(
          com.deepseekharness.app.util.UiText.format("部分旧环境待清理：%s", cleanup.getMessage()));
    }
    if (!actions.current()) return null;
    // rc1 会在第一次真实启动时迁移 Session/settings/profile。先在 .dsh
    // 隔离区留住旧源和摘要；迁移保护失败时不继续启动，避免上游先 rename
    // settings.yaml 后因插件 pending 造成不可重试的导入。
    String migration = runtime.migration(diagnostics.recordId());
    Map<String, Object> migrationState =
        com.deepseekharness.app.util.Rc1MigrationResult.parse(migration);
    if (!com.deepseekharness.app.util.Rc1MigrationResult.isRoutineReceipt(migrationState))
      diagnostics.message(generation, migration);
    if (!com.deepseekharness.app.util.Rc1MigrationResult.allowsStart(migrationState)) {
      throw new java.io.IOException(
          com.deepseekharness.app.util.UiText.format(
              "rc1 迁移快照未完成，已阻止导入；原件保留。请检查存储权限和空间后重试：%s",
              com.deepseekharness.app.util.SensitiveData.redact(migration)));
    }
    if (!actions.current()) return null;
    if (!safeMode) {
      if (Thread.currentThread().isInterrupted())
        throw new java.io.InterruptedIOException("PLUGIN_CAS_COPY_INTERRUPTED");
      actions.stage(
          com.deepseekharness.app.util.UiText.choose(
              "检查旧插件文件链接", "Checking legacy plugin file links"));
      long copied =
          requireCasCopyReceipt(
              com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
                  runtime.materializeCasLinks(), "PLUGIN_CAS_COPY"));
      if (copied > 0)
        diagnostics.message(
            generation,
            com.deepseekharness.app.util.UiText.format("已修复 %s 个旧包文件链接，原链接已保留。", copied));
      if (!actions.current()) return null;
      actions.stage(com.deepseekharness.app.util.UiText.text("注册插件"));
      // 内置插件注册：rootfs 烘焙的实体要登记进 web profile 才会被 dsh 加载。
      // 覆盖安装（rootfs 保留）与全新安装（rootfs 重新解压）都靠这一步补齐；
      // 注册失败时停止在原生恢复页，不能带着一半旧插件继续启动 Web。
      String r;
      try {
        r = runtime.register();
        diagnostics.message(generation, r);
      } catch (IOException incomplete) {
        diagnostics.issue(
            generation,
            "",
            com.deepseekharness.app.util.UiText.format(
                "插件注册失败：%s",
                com.deepseekharness.app.util.SensitiveData.redact(incomplete.getMessage())));
        throw incomplete;
      }
      if (!r.contains("BUILTIN_REGISTER_OK")) {
        String detail = com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(r));
        if (detail.length() > 1200) detail = detail.substring(detail.length() - 1200);
        diagnostics.issue(
            generation, "", com.deepseekharness.app.util.UiText.format("插件注册失败：%s", detail));
        throw new java.io.IOException(
            com.deepseekharness.app.util.UiText.format("内置插件未完整注册，请先修复环境：%s", detail));
      }
      // 覆盖升级时只解除旧版自动生成的待审阅标记；尊重用户主动禁用。
      String migratedPlugins = runtime.legacyPlugins();
      diagnostics.message(generation, migratedPlugins);
    }
    if (!actions.current()) return null;
    String startupProfile = "web";
    if (!safeMode) actions.beforeLaunch(diagnostics.recordId());
    if (safeMode) {
      actions.stage(com.deepseekharness.app.util.UiText.text("准备独立基础配置"));
      startupProfile = actions.safeProfile();
      actions.status(
          com.deepseekharness.app.util.UiText.text("安全启动仅加载官方基础界面；原插件开关和配置保留，点普通重启可返回。"));
    }
    if (!actions.unlockStartup()) return null;
    actions.stage(com.deepseekharness.app.util.UiText.text("创建 Web 进程"));
    if (!safeMode)
      try {
        actions.checkpoint(diagnostics.recordId());
      } catch (Exception error) {
        diagnostics.message(
            generation,
            com.deepseekharness.app.util.UiText.format(
                "本次配置快照暂不可用：%s", error.getClass().getSimpleName()));
      }
    return startupProfile;
  }

  /** Read-only compatibility fallback; Web's persisted credentials retain their own priority. */
  static Map<String, String> legacyCredentialEnvironment(
      com.deepseekharness.app.util.CredentialRead read,
      java.util.function.Consumer<String> warning) {
    java.util.Objects.requireNonNull(read);
    if (read.state == com.deepseekharness.app.util.CredentialRead.State.AVAILABLE)
      return Map.of("DEEPSEEK_API_KEY", read.requireValue());
    if (read.state != com.deepseekharness.app.util.CredentialRead.State.NOT_CONFIGURED)
      warning.accept(legacyCredentialWarning(read));
    return Map.of();
  }

  private static String legacyCredentialWarning(com.deepseekharness.app.util.CredentialRead read) {
    return "LEGACY_NATIVE_KEY_"
        + read.state.name()
        + "/"
        + read.reason.name()
        + ": "
        + com.deepseekharness.app.util.UiText.choose(
            "旧原生 API Key 无法读取，原记录已保留。本次不注入兼容凭据；可继续在网页配置模型和凭据。",
            "The legacy native API key is unavailable and its saved record was retained. No compatibility key will be injected; configure the model and credentials in Web.");
  }

  /** One completed repair receipt authorizes proceeding; errors and pending work never do. */
  static long requireCasCopyReceipt(String output) throws IOException {
    Map<String, Object> result = null;
    if (output != null)
      for (String raw : output.split("\\r?\\n")) {
        String line = raw.trim();
        if (!line.startsWith("PLUGIN_RESULT: ")) continue;
        if (result != null) throw new IOException("PLUGIN_CAS_COPY_DUPLICATE_RESULT");
        result =
            com.deepseekharness.app.backup.BackupJson.read(
                line.substring("PLUGIN_RESULT: ".length())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                65536);
      }
    if (result == null) throw new IOException("PLUGIN_CAS_COPY_RESULT_MISSING");
    if (!"ok".equals(result.get("status")))
      throw new IOException(
          "PLUGIN_CAS_COPY_NOT_COMPLETED: "
              + com.deepseekharness.app.util.SensitiveData.redact(
                  String.valueOf(result.get("message"))));
    if (!"DSHA_PNPM_CAS_COPY_V1".equals(result.get("algorithm"))
        || !"web".equals(result.get("profile"))
        || !Boolean.TRUE.equals(result.get("storeReadOnly"))
        || !(result.get("changed") instanceof Boolean))
      throw new IOException("PLUGIN_CAS_COPY_RECEIPT_INVALID");
    long copied = com.deepseekharness.app.backup.BackupJson.number(result, "migratedFiles");
    String operation = com.deepseekharness.app.backup.BackupJson.string(result, "operationId");
    boolean changed = Boolean.TRUE.equals(result.get("changed"));
    if (copied < 0
        || copied > 100000
        || changed != (copied > 0)
        || changed && !operation.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)
        || !changed && !operation.isEmpty())
      throw new IOException("PLUGIN_CAS_COPY_RECEIPT_INVALID");
    return copied;
  }
}
