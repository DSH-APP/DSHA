package com.deepseekharness.app.core;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.WebProcessManager;
import java.io.IOException;
import java.util.Map;

/** Preparation only. The controller owns generation, enqueue, process launch and retry. */
final class StartupPipeline {
  interface Actions {
    boolean current();

    /** 用户启动优先：在插件脚本/写入开始前取消低优先级自动查询。 */
    default void yieldPluginQuery() throws Exception {}

    void stage(String stage);

    void status(String status);

    /** 结束已知旧启动器并确认退出；返回非空表示仍未确认，由调用方在同一窗口内等待。 */
    java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> releasePrior()
        throws IOException;

    /**
     * 在停止开始后的同一预算窗口内只读复核旧 guest 与启动器退出；确认后完成释放。
     * 返回 true 表示已确认退出，启动可以继续。
     */
    boolean awaitPriorExit(long deadlineMs) throws InterruptedException;

    /** 单调时钟毫秒值；仅用于计算同一停止窗口，不参与任何退出判定。 */
    long now();

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

    /** 停止旧 Web；返回仍无法确认的停止事实（空表示已确认停止）。 */
    java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> stopWeb() throws IOException;

    void tools() throws Exception;

    boolean ready();

    boolean offlineBundle();

    void extract() throws Exception;

    String migration(String startupId) throws Exception;

    String register() throws Exception;

    String legacyPlugins() throws Exception;

    default Plugins startupPlugins() throws Exception {
      return new Plugins() {
        public String register() throws Exception {
          return Runtime.this.register();
        }

        public String legacy() throws Exception {
          return Runtime.this.legacyPlugins();
        }

        public void close() {}
      };
    }

    com.deepseekharness.app.util.BoundedProcessRunner.Result materializeCasLinks(
        java.util.function.Consumer<String> timing) throws Exception;
  }

  interface Plugins extends AutoCloseable {
    String register() throws Exception;

    String legacy() throws Exception;

    void close();
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

          public java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> stopWeb()
              throws IOException {
            return webProc.stopResult().diagnostics();
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

          public Plugins startupPlugins() throws Exception {
            var scripts = boot.prepareStartupPlugins();
            return new Plugins() {
              public String register() throws Exception {
                return com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
                    scripts.register(), "BUILTIN_REGISTER");
              }

              public String legacy() throws Exception {
                return com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
                    scripts.legacy(), "PLUGIN_LEGACY_ACTIVATION");
              }

              public void close() {
                scripts.close();
              }
            };
          }

          public com.deepseekharness.app.util.BoundedProcessRunner.Result materializeCasLinks(
              java.util.function.Consumer<String> timing) throws Exception {
            return boot.materializeCasLinksResult(timing);
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
    actions.yieldPluginQuery();
    var timing = new StartupPreparationTiming(line -> diagnostics.message(generation, line));
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
    long stopDeadline =
        actions.now() + com.deepseekharness.app.util.WebStopBudget.STARTUP_EXIT_MILLIS;
    java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> stopFacts =
        timing.run("stop-web", runtime::stopWeb);
    // 旧 guest 报告“正在退出”时，已知启动器仍要先拿到同样的优雅退出窗口；旧实现一旦
    // stop-web 非空就立刻抛出，导致 10ms 的扫描把一次正常退出判成启动失败。
    java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> priorFacts =
        timing.run("retire-prior-launchers", actions::releasePrior);
    if (!stopFacts.isEmpty() || !priorFacts.isEmpty()) {
      // 已被新的停止/启动意图取代时不再等待：等待只属于仍然有效的这一代启动。
      if (!actions.current()) return null;
      java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> pending =
          new java.util.ArrayList<>(stopFacts);
      pending.addAll(priorFacts);
      boolean settled = false;
      if (com.deepseekharness.app.util.WebStopBudget.mayStillExit(pending))
        settled = timing.run("wait-prior-exit", () -> actions.awaitPriorExit(stopDeadline));
      if (!settled) throw new StartupStopFailure(StartupStopText.unconfirmed(pending), pending);
    }
    if (!actions.current()) return null;
    actions.stage(com.deepseekharness.app.util.UiText.text("检查随包运行工具"));
    timing.run(
        "runtime-tools",
        () -> {
          runtime.tools();
          return null;
        });
    if (!runtime.ready()) {
      if (!runtime.offlineBundle()) {
        throw new IOException("BUNDLED_ENVIRONMENT_MISSING");
      }
      actions.status(com.deepseekharness.app.util.UiText.text("正在解压内置环境（首次约需几分钟，请勿退出）…"));
      actions.stage(com.deepseekharness.app.util.UiText.text("解压运行环境"));
      timing.run(
          "extract-environment",
          () -> {
            runtime.extract();
            return null;
          });
      actions.status(com.deepseekharness.app.util.UiText.text("环境解压完成，正在启动 dsh web…"));
    }
    if (!actions.current()) return null;
    actions.stage(com.deepseekharness.app.util.UiText.text("恢复数据事务"));
    timing.run(
        "recover-data",
        () -> {
          actions.recoverData();
          return null;
        });
    try {
      timing.run(
          "cleanup-retired",
          () -> {
            actions.cleanup();
            return null;
          });
    } catch (IOException cleanup) {
      actions.status(
          com.deepseekharness.app.util.UiText.format("部分旧环境待清理：%s", cleanup.getMessage()));
    }
    if (!actions.current()) return null;
    // rc1 会在第一次真实启动时迁移 Session/settings/profile。先在 .dsh
    // 隔离区留住旧源和摘要；迁移保护失败时不继续启动，避免上游先 rename
    // settings.yaml 后因插件 pending 造成不可重试的导入。
    String migration =
        timing.run("rc-one-migration", () -> runtime.migration(diagnostics.recordId()));
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
      var outcome =
          timing.run(
              "legacy-cas-links",
              () -> runtime.materializeCasLinks(line -> diagnostics.message(generation, line)));
      relayCasTiming(outcome.output, generation);
      long copied =
          requireCasCopyReceipt(
              com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
                  outcome, "PLUGIN_CAS_COPY"));
      if (copied > 0)
        diagnostics.message(
            generation,
            com.deepseekharness.app.util.UiText.format("已修复 %s 个旧包文件链接，原链接已保留。", copied));
      if (!actions.current()) return null;
      actions.stage(com.deepseekharness.app.util.UiText.text("注册插件"));
      try (Plugins plugins = timing.run("prepare-plugin-helpers", runtime::startupPlugins)) {
        // 内置插件注册：rootfs 烘焙的实体要登记进 web profile 才会被 dsh 加载。
        // 覆盖安装（rootfs 保留）与全新安装（rootfs 重新解压）都靠这一步补齐；
        // 注册失败时停止在原生恢复页，不能带着一半旧插件继续启动 Web。
        String r;
        try {
          r = timing.run("register-builtins", plugins::register);
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
        if (!actions.current()) return null;
        String migratedPlugins = timing.run("legacy-plugin-reviews", plugins::legacy);
        diagnostics.message(generation, migratedPlugins);
      }
    }
    if (!actions.current()) return null;
    String startupProfile = "web";
    if (!safeMode)
      timing.run(
          "plugin-activation",
          () -> {
            actions.beforeLaunch(diagnostics.recordId());
            return null;
          });
    if (safeMode) {
      actions.stage(com.deepseekharness.app.util.UiText.text("准备独立基础配置"));
      startupProfile = timing.run("safe-profile", actions::safeProfile);
      actions.status(
          com.deepseekharness.app.util.UiText.text("安全启动仅加载官方基础界面；原插件开关和配置保留，点普通重启可返回。"));
    }
    if (!timing.run("unlock-startup", actions::unlockStartup)) return null;
    actions.stage(com.deepseekharness.app.util.UiText.text("创建 Web 进程"));
    if (!safeMode)
      try {
        timing.run(
            "configuration-checkpoint",
            () -> {
              actions.checkpoint(diagnostics.recordId());
              return null;
            });
      } catch (Exception error) {
        diagnostics.message(
            generation,
            com.deepseekharness.app.util.UiText.format(
                "本次配置快照暂不可用：%s",
                com.deepseekharness.app.backup.ConfigurationSnapshots.errorCode(error)));
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
    return com.deepseekharness.app.util.UiText.format(
        com.deepseekharness.app.util.UiText.choose(
            "LEGACY_NATIVE_KEY_%s/%s: 旧原生 API Key 无法读取，原记录已保留。本次不注入兼容凭据；可继续在网页配置模型和凭据。",
            "LEGACY_NATIVE_KEY_%s/%s: The legacy native API key is unavailable and its saved record was retained. No compatibility key will be injected; configure the model and credentials in Web."),
        read.state.name(),
        read.reason.name());
  }

  /** 只转送有界阶段诊断；阶段行不能代替完成回执和真实进程退出。 */
  private void relayCasTiming(String output, long generation) {
    int count = 0;
    if (output == null) return;
    for (String line : output.split("\\r?\\n")) {
      if (!line.startsWith("CAS_TIMING: ")) continue;
      if (++count > 24) break;
      diagnostics.message(
          generation,
          com.deepseekharness.app.util.SensitiveData.redact(
              line.substring(0, Math.min(line.length(), 4096))));
    }
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
