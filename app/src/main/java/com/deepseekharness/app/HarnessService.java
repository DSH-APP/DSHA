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
 *  - 看门狗：TCP 探测 WebUI 端口，连续失联自动重启（带冷却防风暴）；
 *  - WakeLock/WifiLock 息屏保活（熄屏后 node 不被冻结、局域网桥不断）。
 *
 * 适配重启项目（精简版 HarnessController：startWeb(Consumer) / stopWeb / isWebRunning）。
 */
public class HarnessService extends Service {

    public static final String ACTION_START = "com.deepseekharness.app.START";
    public static final String ACTION_STOP = "com.deepseekharness.app.STOP";

    private static final String CHANNEL_ID = "dsh_harness_channel";
    private static final int NOTIF_ID = 1001;

    private HarnessController c;
    private HttpShellService shellHttp;

    // ================= WebUI 监听保活 =================
    private Thread keepAliveThread;
    private volatile boolean keepAliveRunning;
    private final java.util.concurrent.atomic.AtomicLong lastRestartAt =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final long KEEPALIVE_INTERVAL_MS = 15000L;
    private static final long RESTART_COOLDOWN_MS = 120000L;
    private static final int KEEPALIVE_MAX_FAIL = 3;

    /** 息屏保活用的两把锁。 */
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private static volatile HarnessService activeService;
    private final com.deepseekharness.app.util.PowerPolicy powerPolicy = new com.deepseekharness.app.util.PowerPolicy();
    private String notificationState = "";
    private boolean receiverRegistered;
    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshLocks(); }
    };
    public static void refreshPowerMode() {
        HarnessService service = activeService;
        if (service != null) service.refreshLocks();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        c = HarnessController.get(this);
        activeService = this;
        android.content.IntentFilter screen = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver, screen,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        createChannel();
        try {
            showForegroundNotification();
        } catch (RuntimeException error) {
            android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("前台服务未获系统允许: ") + error.getClass().getSimpleName());
            stopSelf();
            return;
        }
        // 3090 桥（agent 调设备能力）随前台服务拉起；跨实例互斥，重复启动安全
        try {
            shellHttp = new HttpShellService(this);
            shellHttp.start();
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
            android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("onStartCommand startForeground 失败: ")
                    + SensitiveData.redact(String.valueOf(e)));
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopWebAndSelf();
            return START_NOT_STICKY;
        }
        startKeepAlive();
        return START_STICKY;
    }

    private void stopWebAndSelf() {
        stopKeepAlive();
        try {
            c.stopWeb(msg -> { });
        } catch (Throwable ignored) {
        }
        try {
            if (shellHttp != null) shellHttp.stop();
        } catch (Throwable ignored) {
        }
        try {
            stopService(new Intent(this, DeviceBridgeService.class));
        } catch (Throwable ignored) {
        }
        stopForeground(true);
        stopSelf();
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
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            if (needWifi && wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DSHA:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
            if (!needWifi && wifiLock != null) {
                if (wifiLock.isHeld()) wifiLock.release();
                wifiLock = null;
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("[保活] 取锁失败（不致命）: ")
                    + SensitiveData.redact(String.valueOf(t)));
        }
    }

    private synchronized void refreshLocks() {
        if (!keepAliveRunning) return;
        com.deepseekharness.app.core.RuntimeTasks.renew();
        boolean eco = c.config().isEcoMode(), starting = c.isStarting();
        boolean active = starting || (!c.isUserStopped() && !c.isRestartBlocked()
                && (c.canAutoRestart() || !c.getWebAuthUrl().isEmpty()));
        boolean lan = c.config().isLanMode(), work = com.deepseekharness.app.core.RuntimeTasks.isBusy();
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean idle = knownWebIdle();
        boolean keep = powerPolicy.keepCpu(eco, active, starting, pm == null || pm.isInteractive(),
                lan, work, idle, android.os.SystemClock.elapsedRealtime());
        if (keep) acquireLocks(!eco || lan || work || !idle); else releaseLocks();
        String state = c.isRestartBlocked() ? com.deepseekharness.app.util.UiText.text("连续失败，自动重启已暂停；点此查看恢复选项")
                : !active ? com.deepseekharness.app.util.UiText.text("Web 已停止") : !eco ? com.deepseekharness.app.util.UiText.text("持续运行 · 后台保活已开启")
                : keep ? com.deepseekharness.app.util.UiText.text("省电模式 · 有任务或状态待确认，继续保活") : com.deepseekharness.app.util.UiText.text("省电模式 · 已空闲，允许系统休眠");
        if (!state.equals(notificationState)) {
            notificationState = state;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            try { if (nm != null) nm.notify(NOTIF_ID, buildNotification(com.deepseekharness.app.util.UiText.text("DSHA 后台服务"), state)); }
            catch (RuntimeException ignored) { }
        }
    }

    private boolean knownWebIdle() {
        if (c.getWebAuthUrl().isEmpty()) return false;
        try {
            java.io.File file = new java.io.File(c.proot().getRootfsDir(), "root/.dsha-web-activity.json");
            if (file.length() > 2048) return false;
            org.json.JSONObject status = new org.json.JSONObject(new String(
                    com.deepseekharness.app.util.Compat.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
            long age = System.currentTimeMillis() - status.getLong("at");
            return status.getLong("generation") == c.getWebGeneration() && age >= 0 && age <= 30_000
                    && status.getBoolean("idle");
        } catch (Exception error) { return false; }
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

    private void startKeepAlive() {
        stopKeepAlive();
        keepAliveRunning = true;
        refreshLocks();
        keepAliveThread = new Thread(() -> {
            int fail = 0;
            while (keepAliveRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(KEEPALIVE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (!keepAliveRunning) break;
                refreshLocks();
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
                if (!c.getWebAuthUrl().isEmpty() && isWebUp()) {
                    c.reportWebHealth(generation, true);
                    fail = 0;
                    continue;
                }
                c.reportWebHealth(generation, false);
                // TCP 探测期间可能发生手动启停，不能沿用旧探测结果。
                if (!keepAliveRunning || Thread.currentThread().isInterrupted()
                        || generation != c.getWebGeneration() || !c.canAutoRestart()) {
                    fail = 0;
                    continue;
                }
                fail++;
                if (fail < KEEPALIVE_MAX_FAIL) continue;
                fail = 0;
                long now = android.os.SystemClock.elapsedRealtime();
                if (lastRestartAt.get() != 0 && now - lastRestartAt.get() < RESTART_COOLDOWN_MS) continue;
                // Controller 在等待鉴权期间始终保持启动门控；等待时间过长不会触发自动重启。
                if (c.restartWebAutomatically(generation, msg -> { })) {
                    lastRestartAt.set(now);
                    android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("[保活] WebUI 连续失联，已提交自动重启"));
                }
            }
        }, "dsha-keepalive");
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

    /** TCP 探测 127.0.0.1:<port> 是否可达（proot 与宿主共享网络栈） */
    private boolean isWebUp() {
        int port;
        try {
            port = c.getWebPort();
        } catch (Exception e) {
            return false;
        }
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        if (activeService == this) activeService = null;
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false; }
        stopKeepAlive();
        if (shellHttp != null) {
            try {
                shellHttp.stop();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showForegroundNotification() {
        Notification notification = buildNotification(com.deepseekharness.app.util.UiText.text("DSHA运行中"), com.deepseekharness.app.util.UiText.text("Web UI 正在后台保持运行"));
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, notification);
    }

    // ================= 通知 =================

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, com.deepseekharness.app.util.UiText.text("DSHA后台服务"), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(com.deepseekharness.app.util.UiText.text("保持 DeepSeek Harness Web UI 后台运行"));
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        // 正文点击必须是一个真正的 Activity PendingIntent。显式复用已有任务并带上
        // open_launch，避免用户已经停在设置/终端页时点击通知看起来「没有反应」。
        // 停止按钮继续使用独立的 Service PendingIntent，不能与正文共用 requestCode。
        Intent intent = new Intent(this, com.deepseekharness.app.ui.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_launch", true);
        PendingIntent pi = PendingIntent.getActivity(this, NOTIF_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, HarnessService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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
