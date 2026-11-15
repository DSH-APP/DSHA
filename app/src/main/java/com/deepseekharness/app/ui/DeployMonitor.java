package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.DeployDisplay;
import com.deepseekharness.app.util.DeployStages;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 部署胶囊 / 维护详情共用的只读订阅：ON_RESUME 起每秒读一次应用级任务快照，ON_PAUSE 停止；
 * 只在显示内容变化时回调（主线程）。就绪与待恢复检查在后台单线程执行并缓存：任务代次 /
 * 状态变化立即重查，空闲时 15 秒兜底，主线程不做磁盘检查。本类不启动、不重试任何任务。
 */
final class DeployMonitor implements DefaultLifecycleObserver {
  private static final ExecutorService IO =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "dsha-deploy-monitor");
            thread.setDaemon(true);
            return thread;
          });

  // 进程级显示记忆：只有本进程观察到运行过的任务才会显示为“已完成”，历史成功不重放。
  private static final Set<Long> OBSERVED = ConcurrentHashMap.newKeySet();
  private static final Map<Long, Boolean> INSTALLED_AT_START = new ConcurrentHashMap<>();
  private static final Map<Long, Integer> REACHED = new ConcurrentHashMap<>();
  private static final Map<Long, DeployStages.Mode> MODES = new ConcurrentHashMap<>();
  private static final Map<Long, Long> SUCCESS_AT = new ConcurrentHashMap<>();
  private static final Set<Long> COLLAPSED = ConcurrentHashMap.newKeySet();

  private final Context context;
  private final BackupTask task;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final java.util.List<Consumer<DeployStages.View>> listeners =
      new java.util.concurrent.CopyOnWriteArrayList<>();
  private DeployStages.View current = DeployStages.idle();
  private String published = "";
  private boolean polling, destroyed, probing;
  private long probeToken;
  private String probedKey = "";
  private long probedAt;
  private Boolean ready;
  private boolean pending, installed;

  DeployMonitor(LifecycleOwner owner, Context context, Consumer<DeployStages.View> listener) {
    this.context = context.getApplicationContext();
    task = BackupTask.get(context);
    if (listener != null) listeners.add(listener);
    owner.getLifecycle().addObserver(this);
  }

  /** 任务由本进程启动时登记：之后的成功可以显示为绿色；记录启动前环境是否已安装以区分首次部署。 */
  static void noteStarted(Context context, long taskId, boolean environmentInstalled) {
    if (taskId <= 0) return;
    OBSERVED.add(taskId);
    INSTALLED_AT_START.putIfAbsent(taskId, environmentInstalled);
  }

  /** 主界面成功胶囊收起：只收起显示，不改任务状态；同一任务收起后不再出现。 */
  static void collapse(long taskId) {
    if (taskId > 0) COLLAPSED.add(taskId);
  }

  static boolean collapsed(long taskId) {
    return COLLAPSED.contains(taskId);
  }

  /** 首次显示成功的时刻（elapsedRealtime）；用于约 5 秒后收起。 */
  static long successSeenAt(long taskId) {
    return SUCCESS_AT.computeIfAbsent(taskId, id -> SystemClock.elapsedRealtime());
  }

  DeployStages.View current() {
    return current;
  }

  /** 最近一次后台就绪检查的结果；尚未检查完成时按未就绪处理。 */
  boolean environmentReady() {
    return Boolean.TRUE.equals(ready);
  }

  /** 最近一次后台检查是否发现未完成的维护事务。 */
  boolean pendingMaintenance() {
    return pending;
  }

  /** 后台检查是否已至少完成一次。 */
  boolean known() {
    return ready != null;
  }

  void subscribe(Consumer<DeployStages.View> listener) {
    if (listener != null && !listeners.contains(listener)) listeners.add(listener);
  }

  void unsubscribe(Consumer<DeployStages.View> listener) {
    listeners.remove(listener);
  }

  /** 立即重读（例如用户刚重试）：就绪检查也立即重查。 */
  void refreshNow() {
    if (destroyed) return;
    probedAt = 0;
    tick();
  }

  @Override
  public void onResume(@NonNull LifecycleOwner owner) {
    if (destroyed) return;
    polling = true;
    probedAt = 0;
    main.removeCallbacks(poll);
    main.post(poll);
  }

  @Override
  public void onPause(@NonNull LifecycleOwner owner) {
    polling = false;
    main.removeCallbacks(poll);
  }

  @Override
  public void onDestroy(@NonNull LifecycleOwner owner) {
    destroyed = true;
    polling = false;
    main.removeCallbacks(poll);
    listeners.clear();
    owner.getLifecycle().removeObserver(this);
  }

  private final Runnable poll =
      new Runnable() {
        @Override
        public void run() {
          if (!polling || destroyed) return;
          tick();
          main.postDelayed(this, 1_000);
        }
      };

  private void tick() {
    BackupTaskState.Snapshot s = task.snapshot();
    boolean running = s.busy();
    if (running && DeployStages.environmentKind(s.kind)) OBSERVED.add(s.id);
    String key = s.id + ":" + s.status + ":" + running;
    long now = SystemClock.elapsedRealtime();
    if (!probing && (!key.equals(probedKey) || now - probedAt >= 15_000)) probe(key);
    // 运行中不依赖就绪结果；终态必须等本代次的后台检查返回后再显示，避免成功先闪成“未通过”。
    if (!running && !key.equals(probedKey)) return;
    if (running && ready != null) INSTALLED_AT_START.putIfAbsent(s.id, installed);
    publish(view(s, running));
  }

  private DeployStages.View view(BackupTaskState.Snapshot s, boolean running) {
    boolean isReady = Boolean.TRUE.equals(ready);
    DeployStages.Phase phase =
        DeployDisplay.phase(s.status, s.kind, running, pending, isReady, OBSERVED.contains(s.id));
    return stagesView(s, phase, installed);
  }

  /**
   * 胶囊与维护详情页共用的阶段视图：显示用途与已到达阶段按任务记在进程内（单调不减；重建一经
   * 确认不回退）。installedNow 只在本进程没有记录任务开始前状态时作为初始用途依据。
   */
  static DeployStages.View stagesView(
      BackupTaskState.Snapshot s, DeployStages.Phase phase, boolean installedNow) {
    if (phase == null || phase == DeployStages.Phase.IDLE) return DeployStages.idle();
    Boolean before = INSTALLED_AT_START.get(s.id);
    // 已识别的显示用途按任务记住：重建一经确认，后续解压行不能把它改回首次部署。
    DeployStages.Mode stored = MODES.get(s.id);
    DeployStages.Mode mode =
        stored != null
            ? stored
            : DeployStages.modeFor(s.kind, before != null ? before : installedNow);
    int reached = stored != null ? REACHED.getOrDefault(s.id, -1) : -1;
    DeployStages.View view = DeployStages.view(mode, phase, reached, s.detail, s.lastStage, s.id);
    if (view.mode != stored) REACHED.put(s.id, view.current);
    else REACHED.merge(s.id, view.current, Math::max);
    MODES.put(s.id, view.mode);
    if (view.phase == DeployStages.Phase.SUCCEEDED) successSeenAt(s.id);
    return view;
  }

  private void publish(DeployStages.View view) {
    current = view;
    String key = DeployDisplay.key(view);
    if (key.equals(published)) return;
    published = key;
    for (Consumer<DeployStages.View> listener : listeners) {
      try {
        listener.accept(view);
      } catch (RuntimeException failure) {
        com.deepseekharness.app.core.DiagnosticLog.record(
            context, "DEPLOY_MONITOR_LISTENER", failure.getClass().getSimpleName());
      }
    }
  }

  private void probe(String key) {
    probing = true;
    long token = ++probeToken;
    IO.execute(
        () -> {
          Boolean isReady = null;
          boolean isPending = false, isInstalled = false;
          try {
            HarnessController controller = HarnessController.get(context);
            isReady = controller.isEnvironmentReady();
            isPending = com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller);
            isInstalled = controller.proot().isEnvironmentInstalled();
          } catch (RuntimeException unavailable) {
            isReady = false;
          }
          final Boolean resultReady = isReady;
          final boolean resultPending = isPending, resultInstalled = isInstalled;
          main.post(
              () -> {
                if (token != probeToken) return;
                probing = false;
                if (destroyed) return;
                ready = resultReady;
                pending = resultPending;
                installed = resultInstalled;
                probedKey = key;
                probedAt = SystemClock.elapsedRealtime();
                tick();
              });
        });
  }
}
