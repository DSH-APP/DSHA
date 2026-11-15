package com.deepseekharness.app.plugins;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.backup.NativeBackupJobs;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.util.PluginSource;
import com.deepseekharness.app.util.SensitiveData;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 应用级管理入口；页面只提交意图，原有仓储继续独占 guest、事务和页面状态。 */
public final class PluginManagementEngine implements AutoCloseable {
  public static final long SNAPSHOT_TTL_MS = 60_000;
  private static final long RUNTIME_COALESCE_MS = 200;

  private final Application application;
  private final PluginRepository repository;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Background worker = new Background();
  private final PluginChangeDetector detector;
  private final Coordinator<PluginBackendAdapter.ResolvedSource> coordinator;
  private final BackupBoundary backupBoundary = new BackupBoundary();
  private final Runnable readyChanged = this::readyChanged;
  private final Observer<NativeBackupJobs.State> backupChanged = this::backupChanged;
  // 生命周期状态只由主线程修改；后台携带不可变来源，完成后投递回主线程。
  private boolean foreground, closed, signalsLoading, signalsAttached;
  private int watchers;
  private HarnessController signalController;
  private LiveData<NativeBackupJobs.State> backupChanges;
  private volatile long autoQuerySuspendedUntil;

  /** 构造只建立内存协作者，不探测环境、不读目录、不启动 Web。 */
  public PluginManagementEngine(Application application, PluginRepository repository) {
    this.application = Objects.requireNonNull(application);
    this.repository = Objects.requireNonNull(repository);
    detector =
        new PluginChangeDetector(
            () -> dispatch(this.repository::invalidateInstalledState),
            this::runtimeChanged,
            this::readyChanged);
    coordinator =
        new Coordinator<>(
            new Backend<PluginBackendAdapter.ResolvedSource>() {
              @Override
              public Cache cache() {
                PluginRegistry.Snapshot value = repository.registry().snapshot();
                return new Cache(
                    value.version,
                    value.source,
                    value.known,
                    value.stale,
                    value.verifiedAtMillis,
                    value.error);
              }

              @Override
              public PluginBackendAdapter.ResolvedSource probe() throws Exception {
                return repository.probeSource();
              }

              @Override
              public Object source(PluginBackendAdapter.ResolvedSource value) {
                return value.identity;
              }

              @Override
              public void bind(PluginBackendAdapter.ResolvedSource value) {
                if (repository.registry().updateSource(value.identity)) publishSourceChange();
                detector.bind(value.observedPaths, value.identity);
              }

              @Override
              public void query(boolean automatic) {
                if (automatic) repository.refreshAutomatic();
                else repository.refresh();
              }

              @Override
              public boolean autoQueryAllowed() {
                return autoQueriesAllowed();
              }

              @Override
              public void cancelQuery() {
                repository.cancelRefresh();
              }

              @Override
              public void projection() {
                repository.refreshRuntimeProjection();
              }

              @Override
              public void observing(boolean value) {
                detector.foreground(value);
              }

              @Override
              public void unavailable(Exception error) {
                // 队首正在工作时，探测未准入不代表目录失败，也不能采纳其临时 items。
                if (repository.isBusy()) return;
                String message = repository.environmentBlockMessage();
                if (message == null || message.isEmpty())
                  message =
                      error == null
                          ? "环境尚未就绪，目录读取将在可用后进行"
                          : "插件来源暂不可读：" + SensitiveData.redact(String.valueOf(error.getMessage()));
                repository.registry().failSource(message);
                repository.publishCatalog(message);
              }
            },
            new Clock() {
              @Override
              public long now() {
                return android.os.SystemClock.elapsedRealtime();
              }

              @Override
              public long wallTime() {
                return System.currentTimeMillis();
              }
            },
            worker,
            this::dispatch);
    repository.setListener(
        new PluginRepository.Listener() {
          @Override
          public void invalidated() {
            dispatch(() -> coordinator.invalidated());
          }

          @Override
          public void sourceResolved(PluginBackendAdapter.ResolvedSource source) {
            dispatch(() -> coordinator.sourceResolved(source));
          }

          @Override
          public void queryFinished() {
            dispatch(() -> coordinator.queryFinished());
          }
        });
  }

  public static PluginManagementEngine get(Context context) {
    return DshaApp.from(context).pluginManagementEngine();
  }

  private void dispatch(Runnable action) {
    Runnable guarded =
        () -> {
          if (!closed) action.run();
        };
    if (Looper.myLooper() == Looper.getMainLooper()) guarded.run();
    else main.post(guarded);
  }

  private void readyChanged() {
    dispatch(
        () -> {
          if (signalController != null
              && repository.registry().updateWebGeneration(signalController.getWebGeneration()))
            publishSourceChange();
          coordinator.reconcile();
        });
  }

  private void publishSourceChange() {
    // 在途任务可能正在组装回执；此时只发布 Registry，不采纳或覆盖它的临时 items。
    if (repository.isBusy()) repository.publishCatalogState();
    else {
      PluginRepository.State shown = repository.state().getValue();
      repository.publishCatalog(shown == null ? "" : shown.message);
    }
  }

  private void runtimeChanged() {
    dispatch(() -> coordinator.runtimeDirty());
  }

  private void backupChanged(NativeBackupJobs.State value) {
    if (!closed && foreground && value != null && backupBoundary.accept(value.id, value.busy))
      repository.invalidateInstalledState();
  }

  public void ensureLoaded() {
    dispatch(() -> coordinator.ensureLoaded());
  }

  /**
   * 用户启动/重启等优先动作调用；取消自动查询并给启动留出有界收尾窗口。
   * 必须在后台线程调用，不能阻塞主线程。
   */
  public void yieldToUserAction() {
    autoQuerySuspendedUntil = android.os.SystemClock.elapsedRealtime() + 120_000;
    repository.cancelAutomaticQueryForUserAction();
    long deadline = android.os.SystemClock.elapsedRealtime() + 3_000;
    try {
      while (android.os.SystemClock.elapsedRealtime() < deadline
          && (repository.isQueryRunning() || repository.hasPendingAutomaticQuery())) {
        Thread.sleep(20);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean autoQueriesAllowed() {
    return android.os.SystemClock.elapsedRealtime() >= autoQuerySuspendedUntil;
  }

  public void refresh() {
    dispatch(() -> coordinator.refresh());
  }

  public void onForeground() {
    dispatch(
        () -> {
          if (foreground) return;
          foreground = true;
          coordinator.foreground(true);
          attachSignals();
        });
  }

  /**
   * 插件页 / 安装页可见期间持有；只有持有期间才做 TTL 周期核对与自动目录查询。
   *
   * @return 幂等释放；必须在页面 onPause 调用
   */
  public Runnable watch() {
    dispatch(
        () -> {
          if (++watchers == 1) coordinator.watched(true);
        });
    java.util.concurrent.atomic.AtomicBoolean held =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    return () -> {
      if (!held.compareAndSet(true, false)) return;
      dispatch(
          () -> {
            if (watchers > 0 && --watchers == 0) coordinator.watched(false);
          });
    };
  }

  public void onBackground() {
    dispatch(
        () -> {
          if (!foreground) return;
          foreground = false;
          coordinator.foreground(false);
          detachSignals();
        });
  }

  private void attachSignals() {
    if (signalsAttached || signalsLoading || !foreground) return;
    if (signalController != null && backupChanges != null) {
      signalsAttached = true;
      signalController.addReadyWebPageListener(readyChanged);
      backupChanges.observeForever(backupChanged);
      // 订阅之前可能已发生切换；先撤销旧代证据，再只读核对来源。
      readyChanged();
      return;
    }
    signalsLoading = true;
    // 两个服务首次创建可能读盘；即使 idle 回调也不能在主线程创建它们。
    worker.execute(
        () -> {
          HarnessController controller;
          LiveData<NativeBackupJobs.State> changes;
          try {
            controller = HarnessController.get(application);
            changes = NativeBackupJobs.get(application).changes();
          } catch (RuntimeException unavailable) {
            dispatch(() -> signalsLoading = false);
            return;
          }
          dispatch(
              () -> {
                signalsLoading = false;
                signalController = controller;
                backupChanges = changes;
                attachSignals();
              });
        });
  }

  private void detachSignals() {
    if (!signalsAttached) return;
    signalsAttached = false;
    signalController.removeReadyWebPageListener(readyChanged);
    backupChanges.removeObserver(backupChanged);
  }

  @Override
  public void close() {
    dispatch(
        () -> {
          foreground = false;
          detachSignals();
          repository.setListener(null);
          coordinator.close();
          detector.close();
          closed = true;
        });
  }

  // 保留仓储原有页面 API 和返回类型，安装、取消与导出均由原执行器处理。
  public PluginRepository repository() {
    return repository;
  }

  public PluginRegistry registry() {
    return repository.registry();
  }

  public LiveData<PluginRepository.State> state() {
    return repository.state();
  }

  public LiveData<PluginRepository.Preview> preview() {
    return repository.preview();
  }

  public PluginOperationQueue.Operation operation(String id) {
    return repository.operation(id);
  }

  public PluginRepository.Preview operationPreview(String id) {
    return repository.operationPreview(id);
  }

  public boolean isBusy() {
    return repository.isBusy();
  }

  public boolean hasKnownItems() {
    return repository.hasKnownItems();
  }

  public long installedRevision() {
    return repository.installedRevision();
  }

  public boolean isSafeMode() {
    return repository.isSafeMode();
  }

  public boolean installationSucceeded() {
    return repository.installationSucceeded();
  }

  public String installedDescription() {
    return repository.installedDescription();
  }

  public String environmentBlockMessage() {
    return repository.environmentBlockMessage();
  }

  public List<PluginRepository.PendingReview> pendingReviews() {
    return repository.pendingReviews();
  }

  public void invalidateInstalledState() {
    repository.invalidateInstalledState();
  }

  public void selectionMessage(String message) {
    repository.selectionMessage(message);
  }

  public String inspectCapabilities(PluginRepository.Item item, Consumer<String> shown) {
    return repository.inspectCapabilities(item, shown);
  }

  public String openPendingReview(String id) {
    return repository.openPendingReview(id);
  }

  public String reviewRestored(String operation, String node) {
    return repository.reviewRestored(operation, node);
  }

  public String reviewLegacyPreset(String key) {
    return repository.reviewLegacyPreset(key);
  }

  public String install(PluginSource source) {
    return repository.install(source);
  }

  public String inspect(PluginSource source, String sha256, String name, String version) {
    return repository.inspect(source, sha256, name, version);
  }

  public String confirmPreview() {
    return repository.confirmPreview();
  }

  public String discardPreview() {
    return repository.discardPreview();
  }

  public String checkUpdates(PluginRepository.Item item) {
    return repository.checkUpdates(item);
  }

  public String prepareUpdate(PluginRepository.Item item) {
    return repository.prepareUpdate(item);
  }

  public String rollback(PluginRepository.Item item) {
    return repository.rollback(item);
  }

  public String safeMode(boolean enabled, Runnable afterSuccess) {
    return repository.safeMode(enabled, afterSuccess);
  }

  public String importArchive(Uri uri) {
    return repository.importArchive(uri);
  }

  public String exportArchives(List<String> names, Uri uri) {
    return repository.exportArchives(names, uri);
  }

  public String setEnabled(PluginRepository.Item item, boolean enabled) {
    return repository.setEnabled(item, enabled);
  }

  public String delete(PluginRepository.Item item) {
    return repository.delete(item);
  }

  public void cancelTask() {
    repository.cancelTask();
  }

  public boolean cancelOperation(String id) {
    return repository.cancelOperation(id);
  }

  interface Clock {
    long now();

    long wallTime();
  }

  interface Cancellation {
    void cancel();
  }

  interface Scheduler {
    void execute(Runnable action);

    Cancellation schedule(Runnable action, long delayMillis);

    void close();
  }

  /** 调度只需要快照元数据；不复制目录状态，也不根据通知推导操作成功。 */
  static final class Cache {
    final long version, verifiedAtMillis;
    final Object source;
    final boolean known, stale;
    final String error;

    Cache(
        long version,
        Object source,
        boolean known,
        boolean stale,
        long verifiedAtMillis,
        String error) {
      this.version = version;
      this.source = source;
      this.known = known;
      this.stale = stale;
      this.verifiedAtMillis = verifiedAtMillis;
      this.error = error == null ? "" : error;
    }
  }

  interface Backend<S> {
    Cache cache();

    S probe() throws Exception;

    Object source(S source);

    void bind(S source);

    void query(boolean automatic);

    default boolean autoQueryAllowed() {
      return true;
    }

    default void cancelQuery() {}

    void projection();

    void observing(boolean foreground);

    void unavailable(Exception error);
  }

  /** 纯调度协议：入口和完成通知在主线程，probe/投影在独立后台单调度器。 */
  static final class Coordinator<S> {
    // 活动摘要最多有效 10 秒；到期只重读投影，不自行重排或查询目录。
    private static final long RUNTIME_EXPIRY_MS = 10_001;
    private final Backend<S> backend;
    private final Clock clock;
    private final Scheduler worker;
    private final Executor main;
    private final PluginChangeCoalescer changes = new PluginChangeCoalescer();
    private Cancellation scanTask, periodicTask, runtimeTask, runtimeExpiryTask;
    private boolean foreground, demand, closed, probing, awaitingProbe, queryIssued;
    // 有插件页可见时才做 TTL 周期核对与自动目录查询；App 前台本身只维持廉价的来源核对、变更标记和运行投影。
    private boolean watched;
    private boolean cancellingPrefetch, reconcilePending, sourceUnavailable;
    private long lifecycle, runtimeRevision;
    private long failedAt = -1;
    private Object failedSource;
    private long freshStamp = Long.MIN_VALUE, freshObservedAt, freshFor;
    private Object freshSource;

    Coordinator(Backend<S> backend, Clock clock, Scheduler worker, Executor main) {
      this.backend = backend;
      this.clock = clock;
      this.worker = worker;
      this.main = main;
    }

    void ensureLoaded() {
      if (closed || fresh(backend.cache())) return;
      if (changes.isScanning()) {
        demand = true;
        return;
      }
      if (!mayRetry(backend.cache())) return;
      demand = true;
      changes.requestNow(clock.now());
      scheduleScan();
    }

    void refresh() {
      if (closed) return;
      demand = true;
      changes.requestNow(clock.now());
      scheduleScan();
    }

    void foreground(boolean value) {
      if (closed || foreground == value) return;
      foreground = value;
      lifecycle++;
      backend.observing(value);
      if (value) {
        reconcile();
        scheduleScan();
        schedulePeriodic();
      } else {
        reconcilePending = false;
        cancelScan();
        if (periodicTask != null) periodicTask.cancel();
        if (runtimeTask != null) runtimeTask.cancel();
        if (runtimeExpiryTask != null) runtimeExpiryTask.cancel();
        periodicTask = runtimeTask = runtimeExpiryTask = null;
        if (queryIssued && !demand && !cancellingPrefetch) {
          cancellingPrefetch = true;
          backend.cancelQuery();
        }
        // 自动预取的 probe 可退休；用户已请求的查询可继续，但不保留后台周期工作。
        if (awaitingProbe && !queryIssued && !demand) {
          awaitingProbe = false;
          changes.scanFinished(clock.now());
        }
        if (demand) scheduleScan();
      }
    }

    /**
     * 插件页可见性。对话页等其它界面在前台时不周期跑 guest 目录查询；变更信号保留到下次可见再核对。
     */
    void watched(boolean value) {
      if (closed || watched == value) return;
      watched = value;
      if (value) {
        if (!foreground) return;
        reconcile();
        scheduleScan();
        schedulePeriodic();
      } else {
        if (periodicTask != null) periodicTask.cancel();
        periodicTask = null;
        if (!demand) cancelScan();
      }
    }

    private boolean active() {
      return foreground && watched;
    }

    void invalidated() {
      if (closed) return;
      changes.markDirty(clock.now());
      scheduleScan();
    }

    void reconcile() {
      if (closed || !foreground) return;
      reconcilePending = true;
      reconcileIfIdle();
    }

    private void reconcileIfIdle() {
      if (!reconcilePending || closed || !foreground || probing || changes.isScanning()) return;
      reconcilePending = false;
      probe();
    }

    void sourceResolved(S source) {
      if (closed || source == null) return;
      if (!Objects.equals(backend.cache().source, backend.source(source))) {
        // Repo 可能已发布较新来源；迟到回调不能反向覆盖。
        reconcile();
        return;
      }
      backend.bind(source);
    }

    void queryFinished() {
      if (closed) return;
      awaitingProbe = queryIssued = false;
      Cache cache = backend.cache();
      boolean cancelledPrefetch = cancellingPrefetch;
      boolean resumeCancelled = cancelledPrefetch && (active() || demand) && !fresh(cache);
      cancellingPrefetch = false;
      sourceUnavailable = false;
      demand = demand && (changes.hasPending() || resumeCancelled);
      // 在途期间新到的 READY 是一次独立核对机会；普通失败不会自行再查。
      if (!cache.error.isEmpty() && !cancelledPrefetch && !reconcilePending) {
        failedAt = clock.now();
        failedSource = cache.source;
      } else {
        failedAt = -1;
        freshStamp = Long.MIN_VALUE;
      }
      changes.scanFinished(clock.now());
      // 取消期间发生的前台/页面需求允许一次新读取；普通成功或失败仍不会自行失效。
      if (resumeCancelled) changes.requestNow(clock.now());
      scheduleScan();
      if (!cancelledPrefetch && cache.known && cache.error.isEmpty()) runtimeDirty();
      reconcileIfIdle();
    }

    void runtimeDirty() {
      if (closed || !foreground) return;
      long epoch = lifecycle, revision = ++runtimeRevision;
      if (runtimeExpiryTask != null) runtimeExpiryTask.cancel();
      runtimeExpiryTask =
          worker.schedule(
              () ->
                  main.execute(
                      () -> {
                        if (closed
                            || !foreground
                            || lifecycle != epoch
                            || runtimeRevision != revision) return;
                        runtimeExpiryTask = null;
                        worker.execute(backend::projection);
                      }),
              RUNTIME_EXPIRY_MS);
      if (runtimeTask != null) return;
      runtimeTask =
          worker.schedule(
              () ->
                  main.execute(
                      () -> {
                        if (closed || !foreground || lifecycle != epoch) return;
                        runtimeTask = null;
                        worker.execute(backend::projection);
                      }),
              RUNTIME_COALESCE_MS);
    }

    private void scheduleScan() {
      cancelScan();
      if (closed || (!active() && !demand)) return;
      long delay = changes.nextDelayMillis(clock.now());
      if (delay < 0) return;
      scanTask = worker.schedule(() -> main.execute(this::beginScan), delay);
    }

    private void beginScan() {
      scanTask = null;
      if (closed || (!active() && !demand) || !changes.beginScan(clock.now())) return;
      awaitingProbe = true;
      probe();
    }

    private void probe() {
      if (closed || probing) return;
      probing = true;
      long epoch = lifecycle;
      Cache before = backend.cache();
      worker.execute(
          () -> {
            S source = null;
            Exception failure = null;
            try {
              source = backend.probe();
            } catch (Exception unavailable) {
              failure = unavailable;
            }
            S resolved = source;
            Exception error = failure;
            main.execute(() -> probed(epoch, before, resolved, error));
          });
    }

    private void probed(long epoch, Cache before, S source, Exception error) {
      probing = false;
      if (closed) return;
      try {
        acceptProbe(epoch, before, source, error);
      } finally {
        reconcileIfIdle();
      }
    }

    private void acceptProbe(long epoch, Cache before, S source, Exception error) {
      if (epoch != lifecycle) {
        if (awaitingProbe && !queryIssued) {
          awaitingProbe = false;
          changes.scanFinished(clock.now());
          changes.requestNow(clock.now());
          scheduleScan();
        }
        if (foreground) reconcile();
        return;
      }
      Cache current = backend.cache();
      if (current.version != before.version
          && !Objects.equals(current.source, before.source)
          && (source == null || !Objects.equals(current.source, backend.source(source)))) {
        // 后台读取期间已有来源换代：不绑定旧结果，保留一轮新的有界补偿。
        awaitingProbe = false;
        if (!queryIssued) changes.scanFinished(clock.now());
        changes.requestNow(clock.now());
        scheduleScan();
        return;
      }
      if (source == null) {
        backend.unavailable(error);
        sourceUnavailable = true;
        failedAt = clock.now();
        failedSource = backend.cache().source;
        if (awaitingProbe) {
          awaitingProbe = false;
          demand = demand && changes.hasPending();
          changes.scanFinished(clock.now());
          scheduleScan();
        }
        return;
      }
      if (sourceUnavailable) {
        // 相同来源恢复可读也构成恢复边界；列表自身失败仍沿原冷却处理。
        sourceUnavailable = false;
        failedAt = -1;
      }
      backend.bind(source);
      if (awaitingProbe) {
        awaitingProbe = false;
        issueQuery(!demand);
      } else if (foreground) {
        Cache cache = backend.cache();
        if (watched && !fresh(cache) && mayRetry(cache)) {
          changes.requestNow(clock.now());
          cancelScan();
          if (changes.beginScan(clock.now())) issueQuery(!demand);
          else scheduleScan();
        } else runtimeDirty();
      }
    }

    private void issueQuery(boolean automatic) {
      if (automatic && !backend.autoQueryAllowed()) {
        // 用户动作期间只保留已有快照；下次页面需求或补偿窗口再核对。
        awaitingProbe = false;
        changes.scanFinished(clock.now());
        return;
      }
      if (automatic && !active()) {
        // probe 在途时插件页已离开：本轮不查，保留一次待办到下次可见。
        awaitingProbe = false;
        changes.scanFinished(clock.now());
        changes.requestNow(clock.now());
        return;
      }
      queryIssued = true;
      try {
        backend.query(automatic);
      } catch (RuntimeException rejected) {
        backend.unavailable(rejected);
        queryFinished();
      }
    }

    private boolean mayRetry(Cache cache) {
      return failedAt < 0
          || !Objects.equals(failedSource, cache.source)
          || clock.now() - failedAt >= SNAPSHOT_TTL_MS;
    }

    private boolean fresh(Cache cache) {
      if (!cache.known || cache.stale) return false;
      if (freshStamp != cache.verifiedAtMillis || !Objects.equals(freshSource, cache.source)) {
        freshStamp = cache.verifiedAtMillis;
        freshSource = cache.source;
        freshObservedAt = clock.now();
        long age = clock.wallTime() - cache.verifiedAtMillis;
        freshFor =
            cache.verifiedAtMillis > 0 && age >= 0 && age < SNAPSHOT_TTL_MS
                ? SNAPSHOT_TTL_MS - age
                : 0;
      }
      // 心跳投影改变版本号但不能续租；时钟回拨也不能无限延长缓存。
      return clock.now() - freshObservedAt < freshFor;
    }

    private void schedulePeriodic() {
      if (closed || !active() || periodicTask != null) return;
      long epoch = lifecycle;
      periodicTask =
          worker.schedule(
              () ->
                  main.execute(
                      () -> {
                        if (closed || !active() || lifecycle != epoch) return;
                        periodicTask = null;
                        reconcile();
                        schedulePeriodic();
                      }),
              SNAPSHOT_TTL_MS);
    }

    private void cancelScan() {
      if (scanTask != null) scanTask.cancel();
      scanTask = null;
    }

    void close() {
      if (closed) return;
      closed = true;
      lifecycle++;
      backend.observing(false);
      cancelScan();
      if (periodicTask != null) periodicTask.cancel();
      if (runtimeTask != null) runtimeTask.cancel();
      if (runtimeExpiryTask != null) runtimeExpiryTask.cancel();
      worker.close();
    }
  }

  /** 备份进度与首次 LiveData 重放不是恢复完成；同一终态也只补偿一次。 */
  static final class BackupBoundary {
    private boolean seen, busy;
    private String id = "";

    boolean accept(String nextId, boolean nextBusy) {
      nextId = nextId == null ? "" : nextId;
      boolean changed = seen && !nextBusy && (busy || !id.equals(nextId));
      seen = true;
      busy = nextBusy;
      id = nextId;
      return changed;
    }
  }

  private static final class Background implements Scheduler {
    private final ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(
            1,
            action -> {
              Thread thread = new Thread(action, "plugin-management");
              thread.setDaemon(true);
              return thread;
            });

    Background() {
      executor.setRemoveOnCancelPolicy(true);
      executor.setKeepAliveTime(30, TimeUnit.SECONDS);
      executor.allowCoreThreadTimeOut(true);
      executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public void execute(Runnable action) {
      executor.execute(action);
    }

    @Override
    public Cancellation schedule(Runnable action, long delayMillis) {
      var future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS);
      return () -> future.cancel(false);
    }

    @Override
    public void close() {
      executor.shutdown();
    }
  }
}
