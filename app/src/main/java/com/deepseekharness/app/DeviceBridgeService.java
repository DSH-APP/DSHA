package com.deepseekharness.app;
import com.deepseekharness.app.util.Compat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.AdbResult;
import com.deepseekharness.app.util.AdbEnvironmentTask;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ADB 保活服务：ADB 开关打开时后台周期探测连接，掉线自动重连。
 * 同时挂载 3090 桥（agent 调设备能力）与 Shizuku 备用通道，
 * 并带网络/屏幕事件即时触发 + Doze 下 Alarm 兜底。
 */
public class DeviceBridgeService extends Service {

    private static volatile DeviceBridgeService current;
    private volatile boolean running = false;

    public static final String CHANNEL_ADB = "dsh_adb_watch_channel";
    private static final int WATCH_NOTIF_ID = 3005;

    private final Handler watchHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean probing = new AtomicBoolean(false);
    private volatile Thread probeThread;
    private volatile int consecutiveFailures = 0;
    private volatile long lastFullVerifyAt = 0L;
    private volatile long lastKickAt = 0L;

    private android.net.ConnectivityManager.NetworkCallback netCallback;
    private android.content.BroadcastReceiver screenReceiver;

    private static final long OK_INTERVAL_MS = 60_000L;
    private static final long[] BACKOFF_MS = {3_000L, 6_000L, 12_000L, 24_000L, 45_000L};
    private static final long LONG_FAIL_INTERVAL_MS = 120_000L;
    private static final long KICK_DEBOUNCE_MS = 1_500L;
    private static final long FULL_VERIFY_INTERVAL_MS = 30 * 60 * 1000L;

    public static volatile String adbState = "unknown";
    public static volatile String adbDetail = "";

    public static boolean isAdbEnabled(Context ctx) {
        return ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean("adb_enabled", false);
    }

    public static boolean apply(Context ctx) {
        if (!isAdbEnabled(ctx)) return false;
        if (!com.deepseekharness.app.bridge.LocalNetworkAccess.granted(ctx)) {
            adbState = "permission_required";
            adbDetail = com.deepseekharness.app.util.UiText.text("ADB 等待局域网授权：请到设备能力授权页开启并授权");
            return false;
        }
        if (current != null) return true;
        try {
            Intent i = new Intent(ctx, DeviceBridgeService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            return true;
        } catch (IllegalStateException e) {
            // Android 12+ 后台启动限制也属于此类；等用户回到前台再恢复。
            adbState = com.deepseekharness.app.util.UiText.text("ADB 保活待恢复：请回到 DSHA");
            Log.w("DSHA", com.deepseekharness.app.util.UiText.text("后台暂不能启动 ADB 保活: ") + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            adbState = com.deepseekharness.app.util.UiText.text("ADB 保活启动失败：请在设备能力授权页重试");
            Log.w("DSHA", com.deepseekharness.app.util.UiText.text("ADB 保活启动失败: ") + e.getClass().getSimpleName());
        }
        return false;
    }

    /** 保活服务判断「ADB 设备桥是否还活着」用（被杀后由看门狗拉回）。 */
    public static boolean isRunning() {
        return current != null;
    }

    public static void kickNow(Context ctx, String reason) {
        DeviceBridgeService svc = current;
        if (svc != null) {
            svc.kick(reason);
        } else {
            apply(ctx);
        }
    }

    /** 配对页的验证结果与设备能力授权页使用同一状态命名。 */
    public static void recordPairResult(Context ctx, AdbResult.PairState result, String output) {
        adbState = result == AdbResult.PairState.CONNECTED ? "connected"
                : result == AdbResult.PairState.PAIRED ? "reconnecting" : "need_manual";
        adbDetail = result == AdbResult.PairState.CONNECTED ? com.deepseekharness.app.util.UiText.text("配对页已验证设备连接")
                : result == AdbResult.PairState.PAIRED ? com.deepseekharness.app.util.UiText.text("配对完成，等待连接验证") : com.deepseekharness.app.util.UiText.text("配对或连接验证未完成，请查看配对页详情");
        DeviceBridgeService svc = current;
        if (svc != null && result == AdbResult.PairState.CONNECTED) {
            svc.lastFullVerifyAt = System.currentTimeMillis();
            svc.consecutiveFailures = 0;
        }
        if (isAdbEnabled(ctx)) apply(ctx);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        running = true;
        createChannel();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34)
                startForeground(WATCH_NOTIF_ID, buildNotification(com.deepseekharness.app.util.UiText.text("ADB 通道保活中")),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(WATCH_NOTIF_ID, buildNotification(com.deepseekharness.app.util.UiText.text("ADB 通道保活中")));
        } catch (RuntimeException error) {
            adbState = com.deepseekharness.app.util.UiText.text("ADB 保活未获系统允许，请回到 DSHA 重试");
            stopSelf();
            return;
        }
        // 3090 桥（agent 调设备能力的通道）与 Shizuku 备用通道一并拉起。
        // 桥有跨实例互斥（STARTED），dsh 启动路径若已起过这里就是幂等 no-op。
        try {
            new HttpShellService(this).start();
        } catch (Throwable e) {
            Log.w("DSHA", com.deepseekharness.app.util.UiText.text("3090 桥启动失败: ") + SensitiveData.redact(String.valueOf(e)));
        }
        try {
            ShizukuShell.ensureBound(this);
        } catch (Throwable e) {
            Log.w("DSHA", com.deepseekharness.app.util.UiText.text("Shizuku 绑定失败: ") + SensitiveData.redact(String.valueOf(e)));
        }
        startKeepAlive();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (probeThread != null) probeThread.interrupt();
        current = null;
        adbState = "disabled";
        adbDetail = com.deepseekharness.app.util.UiText.text("ADB 保活已停止");
        watchHandler.removeCallbacksAndMessages(null);
        try {
            if (netCallback != null) {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (screenReceiver != null) unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
        }
        AdbKeepAliveReceiver.cancel(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ADB, com.deepseekharness.app.util.UiText.text("ADB 设备通道"), NotificationManager.IMPORTANCE_LOW));
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text) {
        return new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ADB)
                .setContentTitle("DSHA")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(dshaNotificationIntent(false))
                .setAutoCancel(false)
                .build();
    }

    /** 通知正文点击回到 DSHA；不会直接跳到外部页面或自动启动 Web。 */
    private PendingIntent dshaNotificationIntent(boolean openWeb) {
        Intent intent = new Intent(this, com.deepseekharness.app.ui.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_launch", true);
        if (openWeb) intent.putExtra("open_web", true);
        return PendingIntent.getActivity(this, 3006, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void startKeepAlive() {
        watchHandler.postDelayed(periodicProbe, 15_000L);
        startNetworkWatcher();
        startScreenWatcher();
        AdbKeepAliveReceiver.schedule(this);
    }

    /** 网络恢复立刻重连（不然要等下一个周期，WiFi 切换后能白等半分钟）。 */
    private void startNetworkWatcher() {
        try {
            final android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    kick(com.deepseekharness.app.util.UiText.text("网络恢复"));
                }

                @Override
                public void onLost(android.net.Network network) {
                    setAdbState("network_lost", com.deepseekharness.app.util.UiText.text("网络断开，等待恢复"));
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(netCallback);
            } else {
                // Android 6 没有 registerDefaultNetworkCallback，退回到显式网络请求监听
                cm.registerNetworkCallback(
                        new android.net.NetworkRequest.Builder()
                                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                .build(), netCallback);
            }
        } catch (Throwable e) {
            Log.w("DSHA-ADB", com.deepseekharness.app.util.UiText.text("网络监听注册失败: ") + SensitiveData.redact(String.valueOf(e)));
        }
    }

    /** 屏幕点亮/解锁时探一次：用户开始用手机的时刻，正是最需要连接就绪的时刻。 */
    private void startScreenWatcher() {
        try {
            screenReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    kick(com.deepseekharness.app.util.UiText.text("屏幕点亮/解锁"));
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenReceiver, f);
        } catch (Throwable e) {
            Log.w("DSHA-ADB", com.deepseekharness.app.util.UiText.text("屏幕广播注册失败: ") + SensitiveData.redact(String.valueOf(e)));
        }
    }

    private final Runnable periodicProbe = new Runnable() {
        @Override
        public void run() {
            if (!running || !isAdbEnabled(DeviceBridgeService.this)) return;
            probeAsync(com.deepseekharness.app.util.UiText.text("周期"));
            watchHandler.postDelayed(this, nextDelayMs());
        }
    };

    private long nextDelayMs() {
        if (consecutiveFailures == 0) return OK_INTERVAL_MS;
        if (consecutiveFailures <= BACKOFF_MS.length) return BACKOFF_MS[consecutiveFailures - 1];
        return LONG_FAIL_INTERVAL_MS;
    }

    private void kick(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastKickAt < KICK_DEBOUNCE_MS) return;
        lastKickAt = now;
        probeAsync(reason);
    }

    private void probeAsync(final String reason) {
        if (!running || !isAdbEnabled(this) || AdbBridge.isPairing()) return;
        if (!probing.compareAndSet(false, true)) return;
        probeThread = new Thread(() -> {
            try {
                AdbBridge.runEnvironmentTask(this, com.deepseekharness.app.util.UiText.text("ADB 后台准备与探活"), () -> {
                    runProbe(reason);
                    return null;
                });
            } catch (AdbEnvironmentTask.Busy e) {
                setAdbState("environment_busy", e.getMessage());
            } catch (Throwable e) {
                Log.w("DSHA-ADB", com.deepseekharness.app.util.UiText.text("保活探测异常: ") + SensitiveData.redact(String.valueOf(e)));
                setAdbState("need_manual", com.deepseekharness.app.util.UiText.text("保活探测未完成：") + SensitiveData.redact(String.valueOf(e)));
            } finally {
                probing.set(false);
            }
        }, "dsha-adb-watchdog");
        probeThread.start();
    }

    private void runProbe(String reason) {
        if (!running || !isAdbEnabled(this)) return;
        // 3090 桥自愈：桥被系统回收/异常退出后自动补拉起。
        // start() 内部有跨实例互斥（STARTED），重复调用安全；谁抢到端口谁持有。
        try {
            if (!HttpShellService.isReady()) {
                new HttpShellService(this).start();
                if (!HttpShellService.isStarting() && !HttpShellService.bindError().isEmpty())
                    Log.w("DSHA-ADB", com.deepseekharness.app.util.UiText.text("设备确认桥未就绪：") + HttpShellService.bindError());
            }
        } catch (Throwable ignored) {
        }
        HarnessController c = new HarnessController(this);
        ProotBootstrap proot = c.proot();
        if (!proot.isEnvironmentReady()) {
            setAdbState("no_env", com.deepseekharness.app.util.UiText.text("环境未就绪"));
            return;
        }
        if (!AdbBridge.injected(proot)) {
            setAdbState("installing", com.deepseekharness.app.util.UiText.text("正在更新 ADB 脚本与授权设置"));
            String prepared = AdbBridge.ensureReady(this, proot);
            if (AdbResult.marker(prepared, "ENVIRONMENT_BUSY")) {
                setAdbState("environment_busy", prepared);
                return;
            }
            if (!AdbResult.marker(prepared, "SETUP_DONE")) {
                consecutiveFailures++;
                setAdbState("need_manual", com.deepseekharness.app.util.UiText.text("ADB 准备失败：") + SensitiveData.redact(prepared));
                return;
            }
        }
        if (!running || !isAdbEnabled(this) || AdbBridge.isPairing()) return;
        // 1) 无副作用 TCP 探活（避免每轮都触发系统「已连接无线调试」toast）
        boolean needFull = System.currentTimeMillis() - lastFullVerifyAt > FULL_VERIFY_INTERVAL_MS;
        if (!needFull && tcpReachable(readConnectPort(proot), 1200)) {
            onProbeOk(reason);
            return;
        }
        // 2) 完整握手验证（无副作用探活失败时才做）
        String r = probeCommand(proot, null);
        if (AdbResult.shellReady(r)) {
            lastFullVerifyAt = System.currentTimeMillis();
            onProbeOk(reason);
            return;
        }
        if (r != null && r.contains("NO_KEY:")) {
            consecutiveFailures++;
            setAdbState("need_pair", com.deepseekharness.app.util.UiText.text("尚未配对，请到设备能力授权页完成一次无线配对"));
            return;
        }
        if (r != null && r.contains("DEPS_MISSING")) {
            setAdbState("installing", com.deepseekharness.app.util.UiText.text("正在补装 ADB 依赖"));
            String prepared = AdbBridge.ensureReady(this, proot);
            if (AdbResult.marker(prepared, "ENVIRONMENT_BUSY")) {
                setAdbState("environment_busy", prepared);
                return;
            }
            if (!AdbResult.marker(prepared, "SETUP_DONE")) setAdbState("need_manual", SensitiveData.redact(prepared));
            consecutiveFailures++;
            return;
        }
        // 3) mDNS 重发现连接端口 → 重试
        setAdbState("reconnecting", com.deepseekharness.app.util.UiText.text("触发原因：") + reason);
        AdbBridge.Endpoint endpoint = discoverConnPortSync();
        if (endpoint != null) {
            String r2 = probeCommand(proot, endpoint);
            if (AdbResult.shellReady(r2)) {
                lastFullVerifyAt = System.currentTimeMillis();
                onProbeOk(com.deepseekharness.app.util.UiText.text("重连端口 ") + endpoint.port);
                return;
            }
            if (r2 != null && (r2.contains("Unauthorized") || r2.contains("unauthorized")
                    || r2.contains("AUTH"))) {
                consecutiveFailures++;
                setAdbState("need_pair", com.deepseekharness.app.util.UiText.text("配对已失效，需要重新配对"));
                notifyAdbProblem(com.deepseekharness.app.util.UiText.text("需要重新配对"), com.deepseekharness.app.util.UiText.text("配对信息已失效，请到「设备能力授权」页重新配对一次"));
                return;
            }
        }
        // 4) 自动重开无线调试
        if (tryReopenWirelessDebug()) {
            AdbBridge.Endpoint p2 = null;
            long deadline = System.currentTimeMillis() + 12_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                    break;
                }
                p2 = discoverConnPortSync();
                if (p2 != null) break;
            }
            if (p2 != null) {
                String r3 = probeCommand(proot, p2);
                if (AdbResult.shellReady(r3)) {
                    lastFullVerifyAt = System.currentTimeMillis();
                    onProbeOk(com.deepseekharness.app.util.UiText.text("自动重开无线调试后重连"));
                    return;
                }
            }
            consecutiveFailures++;
            setAdbState(consecutiveFailures >= 3 ? "need_manual" : "reconnecting",
                    com.deepseekharness.app.util.UiText.text("无线调试连接尚未验证；请检查连接端口或重新配对。") + SensitiveData.redact(r));
            return;
        }
        consecutiveFailures++;
        setAdbState("need_manual", com.deepseekharness.app.util.UiText.text("无线调试似乎已关闭（失败 ") + consecutiveFailures + com.deepseekharness.app.util.UiText.text(" 次）"));
        if (consecutiveFailures >= 3) {
            notifyAdbProblem(com.deepseekharness.app.util.UiText.text("ADB 连接已断开"), com.deepseekharness.app.util.UiText.text("自动重连未成功。打开「开发者选项 → 无线调试」后会自动恢复"));
        }
    }

    private void onProbeOk(String detail) {
        consecutiveFailures = 0;
        setAdbState("connected", detail);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(WATCH_NOTIF_ID);
        } catch (Throwable ignored) {
        }
    }

    private int readConnectPort(ProotBootstrap proot) {
        try {
            File f = new File(proot.getRootfsDir(), "root/.dsh/adbkeys/connect_port");
            if (f.isFile()) {
                String t = new String(Compat.readAllBytes(f),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                int v = Integer.parseInt(t);
                if (v > 0 && v < 65536) return v;
            }
        } catch (Throwable ignored) {
        }
        return 5555;
    }

    private boolean tcpReachable(int port, int timeoutMs) {
        if (port <= 0) return false;
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), timeoutMs);
            return sock.isConnected();
        } catch (Throwable e) {
            return false;
        }
    }

    private AdbBridge.Endpoint discoverConnPortSync() {
        if (!running || Thread.currentThread().isInterrupted()) return null;
        return AdbBridge.discover(this, "_adb-tls-connect._tcp.", 5000,
                detail -> setAdbState("reconnecting", detail));
    }

    private String probeCommand(ProotBootstrap proot, AdbBridge.Endpoint endpoint) {
        if (!running || !isAdbEnabled(this) || Thread.currentThread().isInterrupted()) return "STOPPED";
        return AdbBridge.probe(proot, endpoint);
    }

    private boolean tryReopenWirelessDebug() {
        if (!running || !isAdbEnabled(this) || Thread.currentThread().isInterrupted()) return false;
        try {
            boolean hasSecure = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (hasSecure) {
                int cur = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
                if (cur != 1) {
                    Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                    Log.i("DSHA-ADB", com.deepseekharness.app.util.UiText.text("保活：WRITE_SECURE_SETTINGS 已开启无线调试"));
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void setAdbState(String state, String detail) {
        if (!running || current != this) return; // 服务停止后的迟到回调不能覆盖新实例状态。
        adbState = state;
        adbDetail = detail == null ? "" : detail;
    }

    private void notifyAdbProblem(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(WATCH_NOTIF_ID, new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ADB)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(dshaNotificationIntent(false))
                    .setAutoCancel(true)
                    .build());
        } catch (Throwable ignored) {
        }
    }
}
