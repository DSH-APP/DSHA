package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.SensitiveData;

/**
 * 前台保活服务：让 dsh Web UI 在后台稳定常驻。
 *  - startForeground 常驻通知，降低被系统回收概率；
 *  - START_STICKY 被杀后由系统重启；
 *  - 看门狗：本轮端口、官方鉴权和 HTTP 就绪分层检查；仅连续连接拒绝可提交受控重启；
 *  - WakeLock/WifiLock 息屏保活（熄屏后 node 不被冻结、局域网桥不断）。
 *
 * Lifecycle and generation come from the application's HarnessController; this service owns only
 * the foreground notification, health polling and platform power leases.
 */
public class HarnessService extends Service {

  public static final String ACTION_START = "com.deepseekharness.app.START";
  public static final String ACTION_STOP = "com.deepseekharness.app.STOP";
  private static final String ACTION_PREPARE = "com.deepseekharness.app.PREPARE_WEB_START";
  private static final String PREPARATION = "web_start_preparation";

  private static final String CHANNEL_ID = "dsh_harness_channel";
  private static final int NOTIF_ID = com.deepseekharness.app.util.NotificationIds.HARNESS;

  private HarnessController c;
  private volatile HttpShellService.Lease shellHttp;

  // ================= WebUI 监听保活 =================
  private volatile Thread keepAliveThread;
  private volatile boolean keepAliveRunning;
  private final java.util.concurrent.atomic.AtomicLong lastRestartAt =
      new java.util.concurrent.atomic.AtomicLong(0);
  private static final long KEEPALIVE_INTERVAL_MS = 15000L;
  private static final long RESTART_COOLDOWN_MS = 120000L;
  private static final int KEEPALIVE_MAX_FAIL = 3;

  /** 息屏保活用的两把锁。 */
  private android.os.PowerManager.WakeLock wakeLock;

  private android.net.wifi.WifiManager.WifiLock wifiLock;
  private final com.deepseekharness.app.util.PowerPolicy powerPolicy =
      new com.deepseekharness.app.util.PowerPolicy();
  private String notificationState = "";
  private boolean receiverRegistered;
  private volatile boolean foregroundPromoted;
  private int foregroundType;
  private final android.content.BroadcastReceiver screenReceiver =
      new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          refreshLocks();
        }
      };

  public static void refreshPowerMode(Context context) {
    HarnessService service = DshaApp.from(context).harnessService().current();
    if (service != null) service.refreshLocks();
  }

  /** Main-thread visible user action only; request the FGS before dispatching startup work. */
  public static com.deepseekharness.app.util.WebStartProtection.Ticket prepareVisibleStart(
      androidx.fragment.app.FragmentActivity activity) throws java.io.IOException {
    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
        || !ForegroundActivity.isResumed(activity))
      throw new java.io.IOException("WEB_START_REQUIRES_VISIBLE_ACTIVITY");
    HarnessController controller = HarnessController.get(activity);
    var ticket = controller.webStartProtection().begin();
    if (ticket == null) throw new java.io.IOException("WEB_START_PREPARATION_BUSY");
    Context app = activity.getApplicationContext();
    try {
      androidx.core.content.ContextCompat.startForegroundService(
          app,
          new Intent(app, HarnessService.class)
              .setAction(ACTION_PREPARE)
              .putExtra(PREPARATION, ticket.id()));
    } catch (RuntimeException unavailable) {
      finishVisibleStart(app, ticket);
      throw new java.io.IOException("WEB_FOREGROUND_SERVICE_UNAVAILABLE", unavailable);
    }
    new android.os.Handler(android.os.Looper.getMainLooper())
        .postDelayed(
            () -> finishVisibleStart(app, ticket),
            com.deepseekharness.app.util.WebStartProtection.PREPARATION_TIMEOUT_MS);
    return ticket;
  }

  /** Never starts a service from the background; retire only this cancelled preparation. */
  public static void finishVisibleStart(
      Context context, com.deepseekharness.app.util.WebStartProtection.Ticket ticket) {
    if (ticket == null) return;
    ticket.close();
    new android.os.Handler(android.os.Looper.getMainLooper())
        .post(
            () -> {
              HarnessService service = DshaApp.from(context).harnessService().current();
              if (service != null) service.retireIdlePreparation();
            });
  }

  /** 保留配置页入口；LAN 与本机对话共用同一个前台运行服务。 */
  public static void ensureLanForeground(Context context) {
    if (!HarnessController.get(context).config().isLanMode()) return;
    ensureRuntimeForeground(context);
  }

  /** 仅在可见界面调用：已启动的 Web 恢复前台服务，不启动新请求或重启 Node。 */
  public static void ensureRuntimeForeground(Context context) {
    HarnessController controller = HarnessController.get(context);
    if (controller.needsWebSessionRestore())
      controller.restoreRunningWebSession(
          () -> {
            var activity = ForegroundActivity.current();
            if (activity != null && ForegroundActivity.isResumed(activity))
              ensureRuntimeForeground(activity);
          });
    else controller.requestWebSessionRecheck(null);
    if (controller.isUserStopped()
        || controller.isStopping()
        || controller.isRestartBlocked()
        || (!controller.isStarting()
            && controller.getWebAuthUrl().isEmpty()
            && !controller.hasUnresolvedWebSession())) return;
    HarnessService service = DshaApp.from(context).harnessService().current();
    try {
      if (service != null && service.keepAliveHealthy()) {
        service.showForegroundNotification();
        service.refreshLocks();
      } else {
        androidx.core.content.ContextCompat.startForegroundService(
            context.getApplicationContext(),
            new Intent(context.getApplicationContext(), HarnessService.class)
                .setAction(ACTION_START));
      }
    } catch (RuntimeException error) {
      com.deepseekharness.app.core.DiagnosticLog.record(
          context,
          "RUNTIME_BACKGROUND_SERVICE",
          "Background service unavailable: " + error.getClass().getSimpleName());
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    c = HarnessController.get(this);
    DshaApp.from(this).harnessService().attach(this);
    c.restoreRunningWebSession(
        () -> {
          if (DshaApp.from(this).harnessService().current() == this && foregroundPromoted) {
            if (c.webStartProtection().hasPreparing() || c.requiresRuntimeProtection()) {
              if (!keepAliveHealthy()) startKeepAlive();
              else refreshLocks();
            } else retireIdlePreparation();
          }
        });
    android.content.IntentFilter screen = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
    screen.addAction(Intent.ACTION_SCREEN_OFF);
    androidx.core.content.ContextCompat.registerReceiver(
        this, screenReceiver, screen, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    receiverRegistered = true;
    createChannel();
    try {
      showForegroundNotification();
    } catch (RuntimeException error) {
      foregroundProtectionFailed();
      android.util.Log.w(
          "DSHA",
          com.deepseekharness.app.util.UiText.format(
              "前台服务未获系统允许：%s", error.getClass().getSimpleName()));
      stopSelf();
      return;
    }
  }

  private void ensureBridge() {
    if (shellHttp != null) return;
    try {
      shellHttp = HttpShellService.acquire(this);
    } catch (Throwable ignored) {
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // Android 8+ 硬性契约：startForegroundService() 拉起的服务必须在 5 秒内 startForeground，
    // 否则被强杀。每次 onStartCommand 无条件先立通知（幂等）。
    try {
      showForegroundNotification();
    } catch (Throwable e) {
      foregroundProtectionFailed();
      android.util.Log.w(
          "DSHA",
          com.deepseekharness.app.util.UiText.format(
              "onStartCommand startForeground 失败：%s", SensitiveData.redact(String.valueOf(e))));
      stopSelf();
      return START_NOT_STICKY;
    }
    if (intent != null && ACTION_PREPARE.equals(intent.getAction())) {
      String id = intent.getStringExtra(PREPARATION);
      if (!c.webStartProtection().matches(id)) {
        return finishPreparationAttempt();
      }
      try {
        if (!keepAliveHealthy()) startKeepAlive();
        else refreshLocks();
        boolean cpuHeld = wakeLock != null && wakeLock.isHeld();
        if (!cpuHeld || !c.webStartProtection().promote(id, this, this::startupProtectionCurrent)) {
          c.webStartProtection().reject(id, "WEB_FOREGROUND_PROTECTION_UNAVAILABLE");
          return finishPreparationAttempt();
        }
      } catch (RuntimeException unavailable) {
        c.webStartProtection().reject(id, "WEB_FOREGROUND_PROTECTION_UNAVAILABLE");
        return finishPreparationAttempt();
      }
      return START_STICKY;
    }
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopWebAndSelf();
      return START_NOT_STICKY;
    }
    // A visible Activity may have queued START just before the user stopped
    // the runtime. Recheck at delivery; a late intent must not restore the
    // stopped notification/bridge. Sticky recovery keeps its existing path.
    if (intent != null
        && ACTION_START.equals(intent.getAction())
        && !c.webStartProtection().hasPreparing()
        && (c.isUserStopped()
            || c.isStopping()
            || c.isRestartBlocked()
            || (!c.isStarting() && c.getWebAuthUrl().isEmpty() && !c.hasUnresolvedWebSession()))) {
      // A queued START cannot remove the protection for an unfinished/unknown stop.
      var stopped = c.lastStopResult();
      if (c.isStopping()
          || (c.isUserStopped()
              && (stopped == null || !stopped.stopped() || !c.lastStopError().isEmpty())))
        return START_NOT_STICKY;
      stopKeepAlive();
      if (shellHttp != null) {
        shellHttp.close();
        shellHttp = null;
      }
      stopForeground(true);
      foregroundPromoted = false;
      stopSelfResult(startId);
      return START_NOT_STICKY;
    }
    if (!c.webStartProtection().hasPreparing() && !c.requiresRuntimeProtection()) {
      retireIdlePreparation();
      return START_NOT_STICKY;
    }
    if (!keepAliveHealthy()) startKeepAlive();
    else refreshLocks();
    return START_STICKY;
  }

  private void stopWebAndSelf() {
    try {
      // WebLifecycleController retires this service only after guest and launcher proof.
      c.stopWeb(msg -> {});
    } catch (RuntimeException failure) {
      com.deepseekharness.app.core.DiagnosticLog.record(
          this, "RUNTIME_STOP_REQUEST", failure.getClass().getSimpleName());
    }
  }

  /** Called on main while the controller holds the same-generation completed-stop fence. */
  public static void retireStoppedRuntime(Context context, long generation) {
    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
      throw new IllegalStateException("RUNTIME_RETIREMENT_REQUIRES_MAIN");
    HarnessService service = DshaApp.from(context).harnessService().current();
    if (service == null) return;
    HarnessController controller = service.c;
    if (controller.webStartProtection().hasPreparing()) return;
    var stopped = controller.lastStopResult();
    if (generation <= 0
        || generation != controller.getWebGeneration()
        || !controller.isUserStopped()
        || controller.isStarting()
        || controller.isStopping()
        || stopped == null
        || !stopped.stopped()
        || !controller.lastStopError().isEmpty()) return;
    service.stopKeepAlive();
    try {
      if (service.shellHttp != null) {
        service.shellHttp.close();
        service.shellHttp = null;
      }
    } catch (RuntimeException failure) {
      com.deepseekharness.app.core.DiagnosticLog.record(
          context, "RUNTIME_SERVICE_RETIREMENT", failure.getClass().getSimpleName());
      return;
    }
    // DeviceBridgeService owns a separate lease and the user's independent ADB intent.
    service.stopForeground(true);
    service.foregroundPromoted = false;
    service.stopSelf();
  }

  /** Pure slot/thread/flag observation; safe under the lifecycle monitor, no controller or Binder. */
  private boolean startupProtectionCurrent() {
    Thread thread = keepAliveThread;
    return foregroundPromoted
        && keepAliveRunning
        && thread != null
        && thread.isAlive()
        && !thread.isInterrupted()
        && DshaApp.from(this).harnessService().current() == this;
  }

  private void retireIdlePreparation() {
    if (c.webStartProtection().hasPreparing() || c.requiresRuntimeProtection()) {
      if (keepAliveRunning) refreshLocks();
      return;
    }
    stopKeepAlive();
    if (shellHttp != null) {
      shellHttp.close();
      shellHttp = null;
    }
    stopForeground(true);
    foregroundPromoted = false;
    stopSelf();
  }

  private int finishPreparationAttempt() {
    retireIdlePreparation();
    // Rejecting an old ticket must not change the sticky lifetime of a current
    // runtime, unknown session or another valid preparation. No work is replayed.
    return c.webStartProtection().hasPreparing() || c.requiresRuntimeProtection()
        ? START_STICKY
        : START_NOT_STICKY;
  }

  // ================= 息屏保活 =================

  private synchronized void acquireLocks(boolean needWifi) {
    try {
      android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
      if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DSHA:web");
        wakeLock.setReferenceCounted(false);
      }
      if (wakeLock != null) wakeLock.acquire(600_000);
      android.net.wifi.WifiManager wm =
          (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
      if (needWifi && wm != null && (wifiLock == null || !wifiLock.isHeld())) {
        wifiLock =
            wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DSHA:wifi");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
      }
      if (!needWifi && wifiLock != null) {
        if (wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
      }
    } catch (Throwable t) {
      android.util.Log.w(
          "DSHA",
          com.deepseekharness.app.util.UiText.format(
              "[保活] 取锁失败（不致命）：%s", SensitiveData.redact(String.valueOf(t))));
    }
  }

  private void refreshLocks() {
    if (!keepAliveRunning) return;
    com.deepseekharness.app.core.RuntimeTasks.renew();
    boolean preparing = c.webStartProtection().hasPreparing();
    boolean eco = c.config().isEcoMode(), starting = preparing || c.isStarting();
    boolean active = preparing || c.requiresRuntimeProtection();
    boolean lan = c.config().isLanMode(), work = com.deepseekharness.app.core.RuntimeTasks.isBusy();
    android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
    // LAN 需要随时接收远端请求；不读取页面空闲文件决定是否允许休眠。
    boolean idle = !lan && knownWebIdle();
    boolean keep;
    synchronized (powerPolicy) {
      keep =
          powerPolicy.keepCpu(
              eco,
              active,
              starting,
              pm == null || pm.isInteractive(),
              lan,
              work,
              idle,
              android.os.SystemClock.elapsedRealtime());
    }
    if (keep) acquireLocks(!eco || lan || work || !idle);
    else releaseLocks();
    String state =
        preparing || c.isStarting()
            ? com.deepseekharness.app.util.UiText.choose(
                "正在准备启动 Web · 后台保护中", "Preparing Web startup · background protection active")
            : c.hasUnresolvedWebSession() && !c.isUserStopped() && !c.isStopping()
                ? com.deepseekharness.app.util.UiText.choose(
                    "正在验证原 Web 状态 · 保留后台保护",
                    "Verifying the existing Web session · background protection retained")
                : c.isRestartBlocked()
                    ? com.deepseekharness.app.util.UiText.text("连续失败，自动重启已暂停；点此查看恢复选项")
                    : !active
                        ? com.deepseekharness.app.util.UiText.text("Web 已停止")
                        : !eco
                            ? com.deepseekharness.app.util.UiText.text("持续运行 · 后台保活已开启")
                            : keep
                                ? com.deepseekharness.app.util.UiText.text("省电模式 · 有任务或状态待确认，继续保活")
                                : com.deepseekharness.app.util.UiText.text("省电模式 · 已空闲，允许系统休眠");
    if (!state.equals(notificationState)) {
      notificationState = state;
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      try {
        if (nm != null)
          nm.notify(
              NOTIF_ID,
              buildNotification(com.deepseekharness.app.util.UiText.text("DSHA 后台服务"), state));
      } catch (RuntimeException ignored) {
      }
    }
  }

  private boolean knownWebIdle() {
    if (c.getWebAuthUrl().isEmpty()) return false;
    try {
      java.io.File file =
          new java.io.File(c.proot().getRootfsDir(), "root/.dsha-web-activity.json");
      if (file.length() > 2048) return false;
      org.json.JSONObject status =
          new org.json.JSONObject(
              new String(
                  com.deepseekharness.app.util.Compat.readAllBytes(file),
                  java.nio.charset.StandardCharsets.UTF_8));
      long age = System.currentTimeMillis() - status.getLong("at");
      return status.getLong("generation") == c.getWebGeneration()
          && age >= 0
          && age <= 30_000
          && status.getBoolean("idle");
    } catch (Exception error) {
      return false;
    }
  }

  private synchronized void releaseLocks() {
    try {
      if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    } catch (Throwable ignored) {
    }
    try {
      if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    } catch (Throwable ignored) {
    }
    wakeLock = null;
    wifiLock = null;
  }

  // ================= 看门狗 =================

  private synchronized boolean keepAliveHealthy() {
    return keepAliveRunning
        && keepAliveThread != null
        && keepAliveThread.isAlive()
        && !keepAliveThread.isInterrupted();
  }

  private void startKeepAlive() {
    stopKeepAlive();
    keepAliveRunning = true;
    refreshLocks();
    ensureBridge();
    keepAliveThread =
        new Thread(
            () -> {
              int fail = 0;
              while (keepAliveRunning && !Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(KEEPALIVE_INTERVAL_MS);
                } catch (InterruptedException e) {
                  break;
                }
                if (!keepAliveRunning) break;
                HttpShellService.Lease bridge = shellHttp;
                try {
                  if (bridge != null) bridge.ensureStarted();
                } catch (RuntimeException error) {
                  com.deepseekharness.app.core.DiagnosticLog.record(
                      this, "BRIDGE_KEEPALIVE", error.getClass().getSimpleName());
                }
                refreshLocks();
                if (!c.webStartProtection().hasPreparing() && !c.requiresRuntimeProtection()) {
                  new android.os.Handler(android.os.Looper.getMainLooper())
                      .post(this::retireIdlePreparation);
                  continue;
                }
                ensureLanListening();
                c.refreshRecoveredWebSession();
                if (c.webStartProtection().hasPreparing() || c.isRestoringWebSession()) {
                  fail = 0;
                  continue;
                }
                if (!c.canAutoRestart()) {
                  fail = 0;
                  continue;
                }
                long generation = c.getWebGeneration();
                // 顺手守着 ADB 设备桥（普通后台服务被回收时拉回来）
                try {
                  if (DeviceBridgeService.isAdbEnabled(HarnessService.this)
                      && !DeviceBridgeService.isRunning()) {
                    DeviceBridgeService.apply(HarnessService.this);
                  }
                } catch (Throwable ignored) {
                }
                com.deepseekharness.app.util.WebHealthProbe.Result health =
                    com.deepseekharness.app.util.WebHealthProbe.probe(
                        c.getWebAuthUrl(),
                        c.getWebPort(),
                        () ->
                            keepAliveRunning
                                && !Thread.currentThread().isInterrupted()
                                && generation == c.getWebGeneration()
                                && c.canAutoRestart(),
                        3000);
                if (health.state == com.deepseekharness.app.util.WebHealthProbe.State.STALE) {
                  fail = 0;
                  continue;
                }
                if (health.healthy()) {
                  c.reportWebHealth(generation, true);
                  fail = 0;
                  continue;
                }
                c.reportWebHealth(generation, false);
                // 探测期间可能发生手动启停，不能沿用旧探测结果。
                if (!keepAliveRunning
                    || Thread.currentThread().isInterrupted()
                    || generation != c.getWebGeneration()
                    || !c.canAutoRestart()) {
                  fail = 0;
                  continue;
                }
                if (!health.restartCandidate()) {
                  fail = 0;
                  com.deepseekharness.app.core.DiagnosticLog.record(
                      this, "WEB_HEALTH", health.state.name());
                  continue;
                }
                fail++;
                if (fail < KEEPALIVE_MAX_FAIL) continue;
                fail = 0;
                long now = android.os.SystemClock.elapsedRealtime();
                if (lastRestartAt.get() != 0 && now - lastRestartAt.get() < RESTART_COOLDOWN_MS)
                  continue;
                // Controller 在等待鉴权期间始终保持启动门控；等待时间过长不会触发自动重启。
                if (c.restartWebAutomatically(generation, msg -> {})) {
                  lastRestartAt.set(now);
                  android.util.Log.w(
                      "DSHA", com.deepseekharness.app.util.UiText.text("[保活] WebUI 连续失联，已提交自动重启"));
                }
              }
            },
            "dsha-keepalive");
    keepAliveThread.setDaemon(true);
    keepAliveThread.start();
  }

  private synchronized void stopKeepAlive() {
    keepAliveRunning = false;
    releaseLocks();
    if (keepAliveThread != null) {
      keepAliveThread.interrupt();
      keepAliveThread = null;
    }
  }

  /** 与 Web 前台服务同寿命，后台监听失败也可恢复，不依赖重新打开页面。 */
  private void ensureLanListening() {
    long generation = c.getWebGeneration();
    if (!keepAliveRunning
        || !c.config().isLanMode()
        || c.isUserStopped()
        || c.isRestartBlocked()
        || !LanProxyService.hasDshAuth(generation)
        || LanProxyService.isBound()) return;
    try {
      LanProxyService.start(
          c.proot().getRootfsDir().getAbsolutePath(), this, c.getWebPort(), generation);
    } catch (RuntimeException error) {
      com.deepseekharness.app.core.DiagnosticLog.record(
          this, "LAN_LISTENER_RETRY", error.getClass().getSimpleName());
    }
  }

  @Override
  public void onDestroy() {
    foregroundPromoted = false;
    long protectedGeneration = c.webStartProtection().serviceDestroyed(this);
    if (protectedGeneration > 0) c.stopWebIfUnchanged(protectedGeneration);
    DshaApp.from(this).harnessService().release(this);
    if (receiverRegistered) {
      unregisterReceiver(screenReceiver);
      receiverRegistered = false;
    }
    stopKeepAlive();
    if (shellHttp != null) {
      try {
        shellHttp.close();
        shellHttp = null;
      } catch (Throwable ignored) {
      }
    }
    super.onDestroy();
  }

  private void foregroundProtectionFailed() {
    foregroundPromoted = false;
    // Preserve the exact admitted owner before cancelling preparation; otherwise
    // onDestroy would lose the generation that needs the ordinary safe Stop.
    long generation = c.webStartProtection().serviceDestroyed(this);
    if (generation > 0) c.stopWebIfUnchanged(generation);
    c.webStartProtection().cancelAll();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private void showForegroundNotification() {
    boolean preparing = c.webStartProtection().hasPreparing() || c.isStarting();
    Notification notification =
        buildNotification(
            preparing
                ? com.deepseekharness.app.util.UiText.choose("DSHA 正在启动", "DSHA starting")
                : com.deepseekharness.app.util.UiText.text("DSHA运行中"),
            preparing
                ? com.deepseekharness.app.util.UiText.choose(
                    "正在建立后台保护并准备启动 Web", "Establishing protection and preparing Web startup")
                : com.deepseekharness.app.util.UiText.text("Web UI 正在后台保持运行"));
    int type = 0;
    if (Build.VERSION.SDK_INT >= 34) {
      type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
      if (c.config().isLanMode())
        type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
    }
    boolean visible = ForegroundActivity.isResumed(ForegroundActivity.current());
    if (foregroundPromoted && (type == foregroundType || !visible)) {
      NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (manager != null) manager.notify(NOTIF_ID, notification);
      return;
    }
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(NOTIF_ID, notification, type);
    } else startForeground(NOTIF_ID, notification);
    foregroundType = type;
    foregroundPromoted = true;
  }

  // ================= 通知 =================

  private void createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel ch =
          new NotificationChannel(
              CHANNEL_ID,
              com.deepseekharness.app.util.UiText.text("DSHA后台服务"),
              NotificationManager.IMPORTANCE_LOW);
      ch.setDescription(
          com.deepseekharness.app.util.UiText.text("保持 DeepSeek Harness Web UI 后台运行"));
      NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null) nm.createNotificationChannel(ch);
    }
  }

  private Notification buildNotification(String title, String text) {
    // 正文点击必须是一个真正的 Activity PendingIntent。显式复用已有任务并带上
    // open_launch，避免用户已经停在设置/终端页时点击通知看起来「没有反应」。
    // 停止按钮继续使用独立的 Service PendingIntent，不能与正文共用 requestCode。
    Intent intent =
        new Intent(this, com.deepseekharness.app.ui.MainActivity.class)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_launch", true);
    PendingIntent pi =
        PendingIntent.getActivity(
            this,
            NOTIF_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Intent stop = new Intent(this, HarnessService.class).setAction(ACTION_STOP);
    PendingIntent stopPi =
        PendingIntent.getService(
            this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_manage)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(pi)
        .setOngoing(true)
        .addAction(0, com.deepseekharness.app.util.UiText.text("停止"), stopPi)
        .build();
  }
}
