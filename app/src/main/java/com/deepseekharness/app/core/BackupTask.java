package com.deepseekharness.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.BackupTaskState.Status;
import com.deepseekharness.app.util.MaintenanceTransaction;
import java.io.IOException;

/** 应用级任务；Activity/Fragment 只读快照，不保存输入副本或后台线程。 */
public final class BackupTask {
  private final Context app;
  private final HarnessController controller;
  private final SharedPreferences saved;
  private final BackupTaskState state = new BackupTaskState();
  private final Object decision = new Object();
  private Boolean accepted;
  private volatile com.deepseekharness.app.backup.BackupControl activeCancellation;
  /*
   * 维护页只是展示状态，不需要每一帧重新遍历所有事务目录。真正开始任务时
   * 仍调用 pendingMaintenance() 做同步复核；这个缓存只供 UI 轮询使用。
   */
  private static final long UI_PENDING_CACHE_MILLIS = 2_000L;
  private final Object pendingCacheLock = new Object();
  private volatile long pendingCacheAt;
  private volatile boolean pendingCacheValue;
  private volatile boolean pendingCacheValid;
  private static final ThreadLocal<com.deepseekharness.app.backup.BackupControl> OWNER_CONTROL =
      new ThreadLocal<>();

  public static com.deepseekharness.app.backup.BackupControl currentControl(
      com.deepseekharness.app.backup.BackupControl.Progress progress) {
    var current = OWNER_CONTROL.get();
    return current == null
        ? new com.deepseekharness.app.backup.BackupControl(progress)
        : current.withProgress(progress);
  }

  public void cancel() {
    if (!cancellable()) return;
    var current = activeCancellation;
    if (current != null) current.cancel();
    synchronized (decision) {
      if (accepted == null) accepted = false;
      decision.notifyAll();
    }
  }

  public boolean cancellable() {
    return true;
  }

  public static BackupTask get(Context context) {
    return com.deepseekharness.app.DshaApp.from(context).backupTask();
  }

  public BackupTask(Context context) {
    this(context.getApplicationContext(), HarnessController.get(context));
  }

  /** 包内 debug fixture 使用独立 Context/Controller，不改写应用单例。 */
  BackupTask(Context context, HarnessController harness) {
    app = context;
    controller = harness;
    saved = context.getSharedPreferences("dsha-data-task", Context.MODE_PRIVATE);
    try {
      state.restore(
          saved.getLong("id", 0),
          com.deepseekharness.app.util.BackupTaskKinds.normalize(saved.getString("kind", "")),
          Status.valueOf(saved.getString("status", "IDLE")),
          saved.getString("detail", ""));
      state.restoreStage(saved.getString("last_stage", ""));
    } catch (RuntimeException e) {
      state.restore(
          0,
          com.deepseekharness.app.util.UiText.text("任务记录"),
          Status.INTERRUPTED,
          com.deepseekharness.app.util.UiText.text("上次任务记录无法读取，请检查数据状态。"));
    }
  }

  public BackupTaskState.Snapshot snapshot() {
    return state.snapshot();
  }

  /** 执行入口使用的同步、不可缓存维护门禁。 */
  public boolean pendingMaintenance() {
    boolean value = MaintenanceCoordinator.pending(controller);
    synchronized (pendingCacheLock) {
      pendingCacheValue = value;
      pendingCacheAt = android.os.SystemClock.elapsedRealtime();
      pendingCacheValid = true;
    }
    return value;
  }

  /**
   * 仅供维护/安装页面的显示轮询。磁盘事务的真正门禁仍在执行入口重新核验，
   * 因而短暂的 UI 缓存不会放行任何写操作。
   */
  public boolean pendingMaintenanceForUi() {
    long now = android.os.SystemClock.elapsedRealtime();
    if (pendingCacheValid && now - pendingCacheAt < UI_PENDING_CACHE_MILLIS)
      return pendingCacheValue;
    synchronized (pendingCacheLock) {
      now = android.os.SystemClock.elapsedRealtime();
      if (!pendingCacheValid || now - pendingCacheAt >= UI_PENDING_CACHE_MILLIS) {
        pendingCacheValue = MaintenanceCoordinator.pending(controller);
        pendingCacheAt = now;
        pendingCacheValid = true;
      }
      return pendingCacheValue;
    }
  }

  private void invalidatePendingCache() {
    synchronized (pendingCacheLock) {
      pendingCacheValid = false;
    }
  }

  /**
   * 外部入口（应急 DSH 的「恢复中断维护」）完成后强制丢弃 UI 缓存。
   *
   * <p>外部恢复不经过本任务，页面若沿用恢复前的 2 秒缓存，会把已经清理完的记录继续显示成
   * “中断维护”。这里只清缓存；下一次 {@link #pendingMaintenanceForUi()} 在渲染线程重扫。
   */
  public void refreshPendingForUi() {
    invalidatePendingCache();
  }

  public boolean busy() {
    return state.busy() || MaintenanceCoordinator.isExclusiveWorkBlocked();
  }

  /** 顶部维护提示只反映数据任务；启动、终端和插件查询也使用执行锁，不能冒充维护。 */
  public boolean maintenanceBusy() {
    return state.busy() || MaintenanceCoordinator.isExclusive();
  }

  private synchronized void persist() throws IOException {
    BackupTaskState.Snapshot s = state.snapshot();
    if (!saved
        .edit()
        .putLong("id", s.id)
        .putString("kind", s.kind)
        .putString("status", s.status.name())
        .putString("detail", s.detail)
        .putString("last_stage", s.lastStage)
        .commit())
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法保存任务状态，已停止操作"));
  }

  private void progress(long id, String detail) {
    state.update(id, Status.RUNNING, detail);
    try {
      persist();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private interface Work {
    String run(long id) throws Exception;
  }

  private synchronized boolean start(
      String requestedKind, boolean recovery, boolean stopWeb, boolean allowForceStop, Work work) {
    String kind = com.deepseekharness.app.util.BackupTaskKinds.normalize(requestedKind);
    invalidatePendingCache();
    if (busy() || (!recovery && pendingMaintenance())) return false;
    com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
        com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(kind);
    if (lease == null) return false;
    // 在持有凭据后重查磁盘门禁，不能使用竞争窗口之前的 pending 快照。
    if (!recovery && pendingMaintenance()) {
      lease.close();
      return false;
    }
    long id = state.start(kind);
    if (id < 0) {
      lease.close();
      return false;
    }
    DiagnosticLog.record(app, "MAINTENANCE_TASK_START", kind + ":" + id);
    try {
      persist();
    } catch (IOException e) {
      state.update(id, Status.FAILED, e.getMessage());
      lease.close();
      return false;
    }
    synchronized (decision) {
      accepted = null;
    }
    var cancellation = new com.deepseekharness.app.backup.BackupControl(null);
    activeCancellation = cancellation;
    final com.deepseekharness.app.backup.DataProtectionService.StartTicket protection;
    try {
      protection = com.deepseekharness.app.backup.DataProtectionService.start(app);
    } catch (IOException error) {
      state.update(id, Status.FAILED, error.getMessage());
      lease.close();
      activeCancellation = null;
      try {
        persist();
      } catch (IOException ignored) {
      }
      return false;
    }
    Thread worker =
        new Thread(
            () -> {
              OWNER_CONTROL.set(cancellation);
              try (lease) {
                try {
                  protection.await();
                  String result =
                      lease.run(
                          () -> {
                            progress(
                                id,
                                stopWeb
                                    ? com.deepseekharness.app.util.UiText.text("正在停止 Web，等待运行队列完成…")
                                    : com.deepseekharness.app.util.UiText.text("正在准备数据快照…"));
                            return stopWeb
                                ? allowForceStop
                                    ? MaintenanceCoordinator.exclusiveUserConfirmed(
                                        controller, () -> work.run(id))
                                    : MaintenanceCoordinator.exclusive(
                                        controller, () -> work.run(id))
                                : work.run(id);
                          });
                  state.update(id, Status.SUCCEEDED, result);
                } catch (Cancelled e) {
                  state.update(
                      id,
                      Status.CANCELLED,
                      com.deepseekharness.app.util.UiText.text("已取消恢复，当前数据未覆盖。"));
                } catch (Exception e) {
                  Status failure =
                      e instanceof java.io.InterruptedIOException && !pendingMaintenance()
                          ? Status.CANCELLED
                          : Status.FAILED;
                  String reason = BackupManager.safeError(e);
                  state.update(id, failure, reason);
                  // 维护 / 恢复失败必须进入可导出的记录，不能只留在本次界面内存里。
                  DiagnosticLog.record(
                      app, "MAINTENANCE_TASK_" + failure.name(), kind + ":" + reason);
                } finally {
                  OWNER_CONTROL.remove();
                  if (activeCancellation == cancellation) activeCancellation = null;
                  invalidatePendingCache();
                  try {
                    persist();
                  } catch (IOException e) {
                    state.update(id, Status.FAILED, e.getMessage());
                  }
                }
              }
            },
            "dsha-data-task");
    try {
      worker.start();
    } catch (RuntimeException e) {
      activeCancellation = null;
      state.update(id, Status.FAILED, BackupManager.safeError(e));
      lease.close();
      try {
        persist();
      } catch (IOException ignored) {
      }
      return false;
    }
    return true;
  }

  public boolean backup(int scope) {
    var selection = nativeBackupSelection(scope);
    selection.includeApiKey = controller.config().isBackupKey();
    return com.deepseekharness.app.backup.NativeBackupJobs.get(app)
        .exportToDownloads(selection, false);
  }

  static com.deepseekharness.app.backup.NativeDataLocations.Selection nativeBackupSelection(
      int scope) {
    var selection = new com.deepseekharness.app.backup.NativeDataLocations.Selection();
    switch (scope) {
      case com.deepseekharness.app.util.BackupScope.FULL ->
          selection.scope = com.deepseekharness.app.util.BackupScopes.APPLICATION;
      case com.deepseekharness.app.util.BackupScope.SESSIONS ->
          selection.scope = com.deepseekharness.app.util.BackupScopes.SESSIONS;
      case com.deepseekharness.app.util.BackupScope.SETTINGS ->
          selection.scope = com.deepseekharness.app.util.BackupScopes.SETTINGS;
      case com.deepseekharness.app.util.BackupScope.PLUGINS ->
          selection.scope = com.deepseekharness.app.util.BackupScopes.PLUGINS;
      default -> throw new IllegalArgumentException("UNKNOWN_BACKUP_SCOPE");
    }
    return selection;
  }

  public boolean prepareRestore(Uri uri) {
    return start(
        com.deepseekharness.app.util.BackupTaskKinds.RESTORE,
        false,
        false,
        false,
        id -> {
          progress(id, com.deepseekharness.app.util.UiText.text("正在读取独立副本并检查归档…"));
          try (BackupManager.PreparedRestore prepared =
              MaintenanceCoordinator.snapshot(
                  controller, () -> BackupManager.prepareRestore(app, controller, uri))) {
            // 预检结束即释放归档锁/CPU 工作锁；等待确认时仅持任务 Lease。
            state.update(
                id,
                Status.PREVIEW,
                com.deepseekharness.app.util.UiText.format(
                    "确认恢复将停止 Web，并中断正在执行的任务。\n\n%s", prepared.summary));
            persist();
            synchronized (decision) {
              while (accepted == null) decision.wait();
              if (!accepted) throw new Cancelled();
            }
            progress(id, com.deepseekharness.app.util.UiText.text("已确认恢复，正在停止 Web 并等待退出…"));
            return MaintenanceCoordinator.exclusiveUserConfirmed(
                controller, () -> BackupManager.restoreWithinDataTask(controller, prepared));
          }
        });
  }

  public boolean decide(long id, boolean restore) {
    synchronized (decision) {
      if (!state.confirm(id) || accepted != null) return false;
      accepted = restore;
      decision.notifyAll();
      return true;
    }
  }

  public boolean rebuild() {
    return start(
        com.deepseekharness.app.util.BackupTaskKinds.REBUILD,
        false,
        true,
        true,
        id -> EnvironmentMaintenance.rebuild(controller, detail -> progress(id, detail)));
  }

  /** 自动开始与首次部署使用：普通停止失败只如实失败，不升级强制结束。 */
  public boolean updateEnvironment() {
    return updateEnvironment(false);
  }

  /** 用户已在维护页显式确认后的更新；{@code allowForceStop} 允许停止流程按身份核验强制结束旧 Web。 */
  public boolean updateEnvironment(boolean allowForceStop) {
    return start(
        com.deepseekharness.app.util.BackupTaskKinds.UPDATE,
        false,
        true,
        allowForceStop,
        id -> EnvironmentMaintenance.update(controller, detail -> progress(id, detail)));
  }

  public boolean recoverMaintenance() {
    return start(
        com.deepseekharness.app.util.BackupTaskKinds.RECOVER,
        true,
        true,
        true,
        id -> EnvironmentMaintenance.recover(controller));
  }

  /** 原生确认后执行；expectedDigest 为确认时展示的计划摘要，执行前在屏障内重新核对。 */
  public boolean isolatePluginJournals(String expectedDigest) {
    return start(
        com.deepseekharness.app.util.BackupTaskKinds.ISOLATE_PLUGIN_JOURNALS,
        true,
        true,
        true,
        id -> PluginJournalIsolation.isolate(controller, expectedDigest));
  }

  public boolean rollbackRuntime(String operation) {
    return start(
        com.deepseekharness.app.util.BackupTaskKinds.ROLLBACK,
        false,
        true,
        true,
        id ->
            EnvironmentMaintenance.rollbackRuntime(
                controller, operation, detail -> progress(id, detail)));
  }

  public boolean selectDataHome(com.deepseekharness.app.backup.UserDataLayout.Home home) {
    return start(
        com.deepseekharness.app.util.BackupTaskKinds.SELECT_HOME,
        true,
        true,
        true,
        id -> {
          // 此动作只选择已有目录，不复制、清空或修复其中的数据。
          var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
          java.io.File files = app.getFilesDir().getCanonicalFile();
          // 数据根无法确定（DATA_LOCATION_*）时各域探测都会读不了；选择目录正是这一状态的出口，
          // 不能被它自己挡住。其余待恢复或读不了的记录仍须先处理。
          var blocking =
              com.deepseekharness.app.backup.HostMaintenancePending.blocksDataHomeSelection(
                  com.deepseekharness.app.backup.HostMaintenancePending.inspectAllWithGuest(files));
          if (blocking != null) throw new IOException("RECOVERY_PENDING: " + blocking.domain);
          var layout = new com.deepseekharness.app.backup.UserDataLayout(fs, files);
          layout.choose(home);
          return com.deepseekharness.app.util.UiText.choose(
              "已选择数据目录，另一份原件仍保留。", "Data directory selected; the other original remains retained.");
        });
  }

  public boolean resetConfig() {
    return start(
        com.deepseekharness.app.util.BackupTaskKinds.RESET,
        false,
        true,
        true,
        id -> {
          progress(
              id,
              com.deepseekharness.app.util.UiText.choose(
                  "正在由 Android 校验并保留重置前配置…",
                  "Android is verifying and retaining the original configuration…"));
          return controller.resetConfig();
        });
  }

  public boolean repairStartup(org.json.JSONObject request) {
    return start(
        com.deepseekharness.app.util.BackupTaskKinds.REPAIR,
        "recover".equals(request.optString("command")),
        true,
        true,
        id -> StartupRepairs.change(app, controller, request));
  }

  public boolean removeStartupPlugin(String name) {
    return start(
        com.deepseekharness.app.util.BackupTaskKinds.REMOVE_PLUGIN,
        false,
        true,
        true,
        id -> StartupRepairs.deletePlugin(controller, name));
  }

  private static final class Cancelled extends Exception {}
}
