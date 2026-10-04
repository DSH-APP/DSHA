package com.deepseekharness.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * 应用入口：全局初始化。
 * 骨架阶段只建一个任务通知渠道；完整版另有配对/确认渠道（见原 Constants.CHANNEL_*）。
 */
public class DshaApp extends Application
    implements com.deepseekharness.app.runtime.RuntimeHostPorts.Owner,
        com.deepseekharness.app.data.PortableSettings.Owner,
        com.deepseekharness.app.backup.MaintenanceUiPorts {

  private final com.deepseekharness.app.runtime.RuntimeHostPorts runtimeHostPorts =
      new com.deepseekharness.app.runtime.RuntimeHostPorts();

  @Override
  public com.deepseekharness.app.runtime.RuntimeHostPorts runtimeHostPorts() {
    return runtimeHostPorts;
  }

  @Override
  public android.content.Intent dataProtectionPage(boolean nativeBackup, long maintenanceId) {
    return nativeBackup
        ? new android.content.Intent(this, com.deepseekharness.app.ui.NativeDataActivity.class)
        : new android.content.Intent(this, com.deepseekharness.app.ui.ExtractActivity.class)
            .putExtra("review_only", true)
            .putExtra("data_task_id", maintenanceId);
  }

  @Override
  public android.content.Intent runtimePage() {
    return new android.content.Intent(this, com.deepseekharness.app.ui.MainActivity.class)
        .addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("open_launch", true);
  }

  private final com.deepseekharness.app.util.ApplicationServiceSlot<HarnessService> harnessService =
      new com.deepseekharness.app.util.ApplicationServiceSlot<>();

  public com.deepseekharness.app.util.ApplicationServiceSlot<HarnessService> harnessService() {
    return harnessService;
  }

  private final com.deepseekharness.app.core.HarnessSessionState harnessSession =
      new com.deepseekharness.app.core.HarnessSessionState();
  private final com.deepseekharness.app.util.ApplicationOwner<
          com.deepseekharness.app.core.HarnessController>
      harnessOwner = new com.deepseekharness.app.util.ApplicationOwner<>();

  /** Legacy controller facades share the application's queue, process records and generation. */
  public com.deepseekharness.app.core.HarnessSessionState harnessSession() {
    return harnessSession;
  }

  public com.deepseekharness.app.core.HarnessController harnessController() {
    return harnessOwner.get(() -> new com.deepseekharness.app.core.HarnessController(this));
  }

  private final com.deepseekharness.app.util.ApplicationOwner<
          com.deepseekharness.app.core.BackupTask>
      backupOwner = new com.deepseekharness.app.util.ApplicationOwner<>();
  private final com.deepseekharness.app.util.ApplicationOwner<
          com.deepseekharness.app.core.InstallRepository>
      installOwner = new com.deepseekharness.app.util.ApplicationOwner<>();
  private final com.deepseekharness.app.util.ApplicationOwner<
          com.deepseekharness.app.core.PluginRepository>
      pluginOwner = new com.deepseekharness.app.util.ApplicationOwner<>();
  private final com.deepseekharness.app.util.ApplicationOwner<
          com.deepseekharness.app.vscreen.VirtualScreenManager>
      virtualScreenOwner = new com.deepseekharness.app.util.ApplicationOwner<>();
  private final com.deepseekharness.app.util.ApplicationOwner<
          com.deepseekharness.app.data.PortableSettings>
      portableSettingsOwner = new com.deepseekharness.app.util.ApplicationOwner<>();

  public com.deepseekharness.app.core.BackupTask backupTask() {
    return backupOwner.get(() -> new com.deepseekharness.app.core.BackupTask(this));
  }

  public com.deepseekharness.app.core.InstallRepository installRepository() {
    return installOwner.get(() -> new com.deepseekharness.app.core.InstallRepository(this));
  }

  public com.deepseekharness.app.core.PluginRepository pluginRepository() {
    return pluginOwner.get(() -> new com.deepseekharness.app.core.PluginRepository(this));
  }

  public com.deepseekharness.app.vscreen.VirtualScreenManager virtualScreenManager() {
    return virtualScreenOwner.get(
        () -> new com.deepseekharness.app.vscreen.VirtualScreenManager(this));
  }

  @Override
  public com.deepseekharness.app.data.PortableSettings portableSettings() {
    return portableSettingsOwner.get(com.deepseekharness.app.data.PortableSettings::new);
  }

  public static DshaApp from(android.content.Context context) {
    java.util.IdentityHashMap<android.content.Context, Boolean> seen =
        new java.util.IdentityHashMap<>();
    android.content.Context current = context;
    for (int depth = 0;
        current != null && depth < 32 && seen.put(current, Boolean.TRUE) == null;
        depth++) {
      if (current instanceof DshaApp) return (DshaApp) current;
      android.content.Context application = current.getApplicationContext();
      if (application instanceof DshaApp) return (DshaApp) application;
      if (current instanceof android.content.ContextWrapper)
        current = ((android.content.ContextWrapper) current).getBaseContext();
      else current = application == current ? null : application;
    }
    throw new IllegalStateException("DSHA_APPLICATION_SCOPE_UNAVAILABLE");
  }

  @Override
  public void onCreate() {
    super.onCreate();
    // 必须早于界面 Locale.setDefault；保留系统原始语言供「跟随系统」使用。
    com.deepseekharness.app.util.SystemLanguage.initialize(this);
    com.deepseekharness.app.data.PortableSettings.initialize(this);
    com.deepseekharness.app.ui.LanguageController.apply(this);
    ShizukuShell.init(this);
    com.deepseekharness.app.ui.ThemeController.apply(this);
    com.deepseekharness.app.core.RuntimeTasks.initialize(this);
    final android.content.Context runtimeApp = getApplicationContext();
    runtimeHostPorts.install(
        new com.deepseekharness.app.runtime.RuntimeHostPorts.Provider() {
          @Override
          public com.deepseekharness.app.runtime.RuntimeHostPorts.Settings snapshot() {
            return new com.deepseekharness.app.core.ConfigStore(runtimeApp)
                .runtimeSettingsSnapshot();
          }

          @Override
          public void stage(String value) {
            com.deepseekharness.app.core.ColdInstallDiagnostics.stage(runtimeApp, value);
          }

          @Override
          public void record(String kind, String detail) {
            com.deepseekharness.app.core.ColdInstallDiagnostics.record(runtimeApp, kind, detail);
          }

          @Override
          public void failure(Throwable error) {
            com.deepseekharness.app.core.ColdInstallDiagnostics.failure(runtimeApp, error);
          }

          @Override
          public String describeWebStop(com.deepseekharness.app.util.WebStopDiagnostic diagnostic) {
            return com.deepseekharness.app.core.WebStopText.render(diagnostic);
          }

          @Override
          public com.deepseekharness.app.runtime.RuntimeTrial.BrowserProbe browserProbe() {
            return com.deepseekharness.app.ui.RuntimeBrowserProbe::open;
          }

          @Override
          public java.util.Map<String, Object> snapshotColdSelection() throws java.io.IOException {
            return new com.deepseekharness.app.core.ConfigStore(runtimeApp)
                .snapshotColdRuntimeSelection();
          }

          @Override
          public void restoreColdSelection(
              java.util.Map<String, Object> before, String expectedRoot)
              throws java.io.IOException {
            new com.deepseekharness.app.core.ConfigStore(runtimeApp)
                .restoreColdRuntimeSelection(before, expectedRoot);
          }

          @Override
          public com.deepseekharness.app.runtime.RuntimeHostPorts.Settings successfulColdRuntime(
              java.io.File rootfs,
              com.deepseekharness.app.util.ColdInstallPlan.Mode mode,
              String packageSlotSha)
              throws java.io.IOException {
            java.io.File current =
                new java.io.File(runtimeApp.getFilesDir(), "linux/ubuntu").getCanonicalFile();
            if (!current.equals(rootfs.getCanonicalFile()))
              throw new java.io.IOException("COLD_RUNTIME_ROOT_CHANGED");
            var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
            var node = fs.stat(current);
            java.io.File toolsVersion =
                new java.io.File(current, "root/.dsha-ubuntu-tools-version");
            if (!node.type.equals("DIRECTORY")
                || !fs.stat(toolsVersion).type.equals("FILE")
                || !new String(
                        fs.small(toolsVersion, 128), java.nio.charset.StandardCharsets.US_ASCII)
                    .matches("[a-f0-9]{64}\\n"))
              throw new java.io.IOException("COLD_RUNTIME_POSTCHECK_MISSING");
            var selected =
                new com.deepseekharness.app.core.ConfigStore(runtimeApp)
                    .recordSuccessfulColdRuntime(
                        mode,
                        current.getPath() + ":" + node.device + ":" + node.key,
                        packageSlotSha);
            com.deepseekharness.app.core.ColdInstallDiagnostics.record(
                runtimeApp,
                "RUNTIME_SELECTED",
                "runtime="
                    + mode.runtime
                    + " prootNoSeccomp="
                    + mode.noSeccomp
                    + " packageSlotSha256="
                    + packageSlotSha);
            return selected;
          }
        });
    com.deepseekharness.app.backup.AutomaticBackups.schedule(this);
    com.deepseekharness.app.backup.PostUpgradeCleanupService.schedule(this);
    com.deepseekharness.app.core.DiagnosticLog.installCrashHandler(this);
    registerActivityLifecycleCallbacks(new com.deepseekharness.app.ui.ModernAndroidUi());
    registerActivityLifecycleCallbacks(new ForegroundActivity());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager nm = getSystemService(NotificationManager.class);
      nm.createNotificationChannel(
          new NotificationChannel(
              "dsh_task_channel",
              com.deepseekharness.app.util.UiText.text("任务通知"),
              NotificationManager.IMPORTANCE_LOW));
    }
  }
}
