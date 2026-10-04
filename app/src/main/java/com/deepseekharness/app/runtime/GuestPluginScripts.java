package com.deepseekharness.app.runtime;

import android.content.Context;
import android.util.Log;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.PluginDownloadSource;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** 通过固定 proot 入口准备并运行插件与 profile 脚本。 */
final class GuestPluginScripts {
  static final String BUILTIN_REGISTER_SCRIPT = "register-builtin-plugins.py";
  static final String RC1_MIGRATION_SCRIPT = "rc1-migration.py";
  static final String PLUGIN_MANAGER_SCRIPT = "plugin-manager.py";
  private static final GuestScriptQueue SCRIPT_QUEUE = new GuestScriptQueue(64);
  static final long MANAGER_TIMEOUT_MS = 3_000_000;

  interface Runner {
    BoundedProcessRunner.Result run(String command, long timeoutMs)
        throws IOException, InterruptedException;
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
        },
        runner);
  }

  GuestPluginScripts(Preparation preparation, Runner runner) {
    this.preparation = java.util.Objects.requireNonNull(preparation);
    this.runner = java.util.Objects.requireNonNull(runner);
  }

  BoundedProcessRunner.Result builtin(String extraArgs) throws IOException, InterruptedException {
    try (GuestScriptQueue.Lease ignored = SCRIPT_QUEUE.enter()) {
      preparePlugins();
      return runner.run(
          "python3 -u /root/.dsh/"
              + BUILTIN_REGISTER_SCRIPT
              + (extraArgs.isEmpty() ? "" : " " + extraArgs)
              + " 2>&1",
          90_000);
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
      return GuestCommandOutput.legacy(builtin(args), 90_000);
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
    java.util.Objects.requireNonNull(source, "downloadSource");
    try (GuestScriptQueue.Lease ignored = SCRIPT_QUEUE.enter()) {
      preparePlugins();
      String task =
          taskId != null && taskId.matches("[a-f0-9]{32}")
              ? "DSHA_PLUGIN_TASK=" + taskId + " "
              : "";
      return runner.run(
          task
              + "DSHA_PLUGIN_DOWNLOAD_SOURCE="
              + source.value
              + " python3 /root/.dsh/"
              + PLUGIN_MANAGER_SCRIPT
              + (args == null || args.isEmpty() ? "" : " " + args)
              + " 2>&1",
          MANAGER_TIMEOUT_MS);
    }
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
    if (!preparation.environmentReady())
      throw new GuestPreparationFailure(GuestPreparationFailure.Code.ENV_NOT_READY);
    if (!preparation.pythonReady())
      throw new GuestPreparationFailure(GuestPreparationFailure.Code.BUNDLED_PYTHON_UNAVAILABLE);
    // pnpm 失败不阻断列表、开关和删除；依赖安装单独报告其失败。
    preparation.pnpm();
    preparation.assets();
  }

  private static String failure(Exception error, String context, String prefix) {
    if (GuestPreparationFailure.is(error, GuestPreparationFailure.Code.ENV_NOT_READY))
      return "ENV_NOT_READY";
    if (GuestPreparationFailure.is(error, GuestPreparationFailure.Code.BUNDLED_PYTHON_UNAVAILABLE))
      return UiText.text("ERROR: Ubuntu Python 环境未就绪");
    if (error instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      return UiText.text("ERROR: 命令等待被中断");
    }
    String detail = SensitiveData.redact(String.valueOf(error));
    Log.w("DSHA", UiText.format(context, detail));
    return prefix + detail;
  }
}
