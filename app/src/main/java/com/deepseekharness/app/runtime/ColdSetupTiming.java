package com.deepseekharness.app.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.MaintenanceCoordinator;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** 在本 UID 的独立空目录走真实冷安装及网页核验，不更改现有环境、数据或偏好。 */
public final class ColdSetupTiming {
  private ColdSetupTiming() {}

  public static double run(Context context, Consumer<String> progress) throws Exception {
    Context app = context.getApplicationContext();
    return MaintenanceCoordinator.exclusive(
        HarnessController.get(app),
        () -> {
          File parent = new File(app.getFilesDir().getCanonicalFile(), "cold-install-probes");
          if (!parent.isDirectory() && !parent.mkdir()) throw new IOException("COLD_TIMING_ROOT");
          File owned = new File(parent, UUID.randomUUID().toString());
          if (!owned.mkdir()) throw new IOException("COLD_TIMING_OWNED_ROOT");
          long started = android.os.SystemClock.elapsedRealtime();
          List<Map<String, Object>> stages = new ArrayList<>();
          Consumer<String> timedProgress =
              stage -> {
                synchronized (stages) {
                  if (stages.isEmpty() || !stage.equals(stages.get(stages.size() - 1).get("stage")))
                    stages.add(
                        Map.of(
                            "stage",
                            stage,
                            "elapsedMillis",
                            android.os.SystemClock.elapsedRealtime() - started));
                }
                progress.accept(stage);
              };
          Scope scoped = new Scope(app, owned, timedProgress);
          ProotBootstrap boot = new ProotBootstrap(scoped, false);
          boolean completed = false;
          try {
            com.deepseekharness.app.core.EnvironmentMaintenance.initializeFresh(
                scoped, boot, timedProgress);
            var health = boot.runtimeHealth();
            if (!boot.isEnvironmentReady()) throw new IOException("COLD_TIMING_NOT_READY");
            double seconds = (android.os.SystemClock.elapsedRealtime() - started) / 1000.0;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", 1L);
            result.put("seconds", seconds);
            result.put("versionCode", (long) com.deepseekharness.app.BuildConfig.VERSION_CODE);
            result.put("runtimeId", boot.expectedRuntimeDescriptor().id());
            result.put("runtimeMode", health.get("runtimeMode"));
            result.put("staticLoader", boot.hostPorts().settings().staticLoader);
            result.put("disableProotSeccomp", boot.hostPorts().settings().disableProotSeccomp);
            result.put("renderer", health.get("renderer"));
            result.put("processExited", health.get("processExited"));
            result.put("hostStageMillis", health.get("hostStageMillis"));
            result.put("nodeStageMillis", health.get("nodeStageMillis"));
            result.put("coldChecks", new LinkedHashMap<>(scoped.diagnostics));
            synchronized (stages) {
              result.put("stages", new ArrayList<>(stages));
            }
            result.put(
                "scope",
                "actual empty private root, signed assets, cold installation and real renderer; existing data untouched");
            var fs = new AndroidBackupFileSystem();
            fs.atomic(parent, "latest.json", BackupJson.write(result, 65536));
            completed = true;
            return seconds;
          } catch (Exception failure) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("seconds", (android.os.SystemClock.elapsedRealtime() - started) / 1000.0);
            failed.put("error", failure.toString());
            failed.put("trial", RuntimeTrial.latestFailure(scoped));
            failed.put("operation", owned.getName());
            failed.put("coldChecks", new LinkedHashMap<>(scoped.diagnostics));
            synchronized (stages) {
              failed.put("stages", new ArrayList<>(stages));
            }
            try {
              new AndroidBackupFileSystem()
                  .atomic(parent, "latest-failure.json", BackupJson.write(failed, 65536));
            } catch (Exception recordFailure) {
              failure.addSuppressed(recordFailure);
            }
            throw failure;
          } finally {
            if (completed) {
              FactoryReset.eraseContents(
                  new AndroidBackupFileSystem(),
                  owned,
                  new BackupControl(null),
                  FactoryReset.STAGE_PRIVATE,
                  new long[] {0, 0});
              if (!owned.delete()) throw new IOException("COLD_TIMING_CLEANUP");
            }
            scoped.clearPreferences();
          }
        });
  }

  public static String report(Context context, boolean success) {
    try {
      var fs = new AndroidBackupFileSystem();
      File path =
          new File(
              context.getFilesDir().getCanonicalFile(),
              "cold-install-probes/" + (success ? "latest.json" : "latest-failure.json"));
      Map<String, Object> row = BackupJson.read(fs.small(path, 65536), 65536);
      StringBuilder text = new StringBuilder();
      text.append("\n").append(row.get("seconds")).append(" s\n");
      if (success && row.get("runtimeMode") instanceof String mode) {
        text.append("runtime: ").append(mode);
        if (mode.equals("proroot")) text.append(" staticLoader=").append(row.get("staticLoader"));
        else text.append(" disableSeccomp=").append(row.get("disableProotSeccomp"));
        text.append('\n');
      }
      Object stages = row.get("stages");
      if (stages instanceof List<?> values)
        for (Object value : values) {
          if (value instanceof Map<?, ?> stage)
            text.append(stage.get("elapsedMillis"))
                .append(" ms: ")
                .append(
                    com.deepseekharness.app.util.UiStateText.render(
                        String.valueOf(stage.get("stage"))))
                .append('\n');
        }
      if (!success) {
        Object trial = row.get("trial");
        if ((!(trial instanceof String)
                || ((String) trial).startsWith("TRIAL_DIAGNOSTIC_UNAVAILABLE:"))
            && row.get("operation") instanceof String id
            && id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)) {
          File previousFiles = new File(path.getParentFile(), id + "/app/files").getCanonicalFile();
          if (previousFiles
                  .getPath()
                  .startsWith(path.getParentFile().getCanonicalPath() + File.separator)
              && fs.stat(previousFiles).type.equals("DIRECTORY")) {
            trial =
                RuntimeTrial.latestFailure(
                    new ContextWrapper(context) {
                      @Override
                      public File getFilesDir() {
                        return previousFiles;
                      }
                    });
          }
        }
        if (trial instanceof String detail) text.append('\n').append(detail);
      }
      if (success)
        for (String key : List.of("hostStageMillis", "nodeStageMillis")) {
          Object timings = row.get(key);
          if (timings instanceof Map<?, ?> values) {
            text.append('\n').append(key).append('\n');
            for (var value : values.entrySet())
              text.append(value.getKey()).append(": ").append(value.getValue()).append(" ms\n");
          }
        }
      if (row.get("coldChecks") instanceof Map<?, ?> checks) {
        text.append("\ncoldChecks\n");
        for (var value : checks.entrySet())
          text.append(value.getKey()).append(": ").append(value.getValue()).append('\n');
      }
      return com.deepseekharness.app.util.SensitiveData.redact(text.toString());
    } catch (Exception unavailable) {
      return "";
    }
  }

  private static final class Scope extends ContextWrapper implements RuntimeHostPorts.Owner {
    final File data, files;
    final RuntimeHostPorts ports = new RuntimeHostPorts();
    final String preferencePrefix = "dsha-cold-timing-" + UUID.randomUUID() + "-";
    final Set<String> preferences = new HashSet<>();
    final Map<String, String> diagnostics = new LinkedHashMap<>();
    RuntimeHostPorts.Settings selected = new RuntimeHostPorts.Settings("auto", false, true, false);

    Scope(Context original, File owned, Consumer<String> progress) throws IOException {
      super(original);
      data = new File(owned, "app");
      files = new File(data, "files");
      if (!files.mkdirs()) throw new IOException("COLD_TIMING_FILES");
      RuntimeTrial.BrowserProbe browser = RuntimeHostPorts.fromOwner(original).browserProbe();
      ports.install(
          new RuntimeHostPorts.Provider() {
            public RuntimeHostPorts.Settings snapshot() {
              return selected;
            }

            public boolean preferFastColdMode() {
              return android.os.Build.VERSION.SDK_INT >= 26
                  && new com.deepseekharness.app.core.ConfigStore(Scope.this).preferFastColdMode();
            }

            public String coldRuntimeIdentity(File root) throws IOException {
              File expected = new File(files, "linux/ubuntu").getCanonicalFile();
              if (!root.getCanonicalFile().equals(expected))
                throw new IOException("COLD_TIMING_SELECTION_ROOT");
              var identity = new AndroidBackupFileSystem().stat(expected);
              if (!identity.type.equals("DIRECTORY"))
                throw new IOException("COLD_TIMING_SELECTION_ROOT");
              return expected.getPath() + ":" + identity.device + ":" + identity.key;
            }

            public void stage(String value) {
              progress.accept(value);
            }

            public void record(String kind, String detail) {
              if (kind.startsWith("CONFIGURED_TOOLS_CHECK_") && diagnostics.size() < 16)
                diagnostics.put(kind, com.deepseekharness.app.util.SensitiveData.redact(detail));
            }

            public void failure(Throwable error) {}

            public Map<String, Object> snapshotColdSelection() throws IOException {
              return new com.deepseekharness.app.core.ConfigStore(Scope.this)
                  .snapshotColdRuntimeSelection();
            }

            public void restoreColdSelection(Map<String, Object> before, String root)
                throws IOException {
              new com.deepseekharness.app.core.ConfigStore(Scope.this)
                  .restoreColdRuntimeSelection(before, root);
              selected =
                  new com.deepseekharness.app.core.ConfigStore(Scope.this)
                      .runtimeSettingsSnapshot();
            }

            public RuntimeTrial.BrowserProbe browserProbe() {
              return browser;
            }

            public RuntimeHostPorts.Settings successfulColdRuntime(
                File root, com.deepseekharness.app.util.ColdInstallPlan.Mode mode, String hash)
                throws IOException {
              if (!root.getCanonicalFile()
                  .equals(new File(files, "linux/ubuntu").getCanonicalFile()))
                throw new IOException("COLD_TIMING_SELECTION_ROOT");
              var fs = new AndroidBackupFileSystem();
              var identity = fs.stat(root);
              selected =
                  new com.deepseekharness.app.core.ConfigStore(Scope.this)
                      .recordSuccessfulColdRuntime(
                          mode, root.getPath() + ":" + identity.device + ":" + identity.key, hash);
              return selected;
            }
          });
    }

    public Context getApplicationContext() {
      return this;
    }

    public File getFilesDir() {
      return files;
    }

    public File getNoBackupFilesDir() {
      File dir = new File(data, "no_backup");
      dir.mkdirs();
      return dir;
    }

    public File getCacheDir() {
      File dir = new File(data, "cache");
      dir.mkdirs();
      return dir;
    }

    public ApplicationInfo getApplicationInfo() {
      ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
      info.dataDir = data.getPath();
      return info;
    }

    public RuntimeHostPorts runtimeHostPorts() {
      return ports;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
      String key = preferencePrefix + name;
      preferences.add(key);
      return super.getSharedPreferences(key, mode);
    }

    void clearPreferences() {
      for (String key : preferences)
        super.getSharedPreferences(key, MODE_PRIVATE).edit().clear().commit();
    }
  }
}
