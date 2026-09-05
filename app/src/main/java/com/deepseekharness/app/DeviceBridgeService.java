package com.deepseekharness.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import androidx.annotation.Nullable;

/**
 * 设备桥服务（普通后台服务，非前台 —— 不 startForeground，杜绝
 * CannotPostForegroundServiceNotificationException 杀进程）。
 *
 * 职责（仅在用户勾选「启用 ADB」后才会启动本服务）：
 *  1. 3090 Shizuku 桥 + Shizuku 绑定；
 *  2. ADB 配对环境后台预热；
 *  3. Nsd 监听无线调试配对弹窗 → 通知里输 6 位码。
 *
 * 通知显示需要 Android 13+ 通知权限；无权限时静默跳过（App 内工作区仍可配对）。
 */
public class DeviceBridgeService extends Service {

    public static final String PREF_ADB = "adb_enabled";

    private static volatile boolean running = false;
    /** 当前服务实例（供外部事件直接触发探测） */
    private static volatile DeviceBridgeService current = null;

    /** 最近一次发现的配对端口（供 AdbPairReceiver 秒级直用） */
    public static volatile int pairPort = 0;
    /** 最近一次发现的配对服务地址（部分 ROM 配对服务只监听 WiFi 接口 IP） */
    public static volatile String pairHost = "";

    public static boolean isAdbEnabled(Context ctx) {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getBoolean(PREF_ADB, false);
    }

    /**
     * 这个服务是否需要在跑。
     *
     * <p>它不只管 ADB：{@code HttpShellService}（127.0.0.1:3090 那座桥）也挂在它身上，
     * 而悬浮条插件与 agent 的设备能力（位置 / 传感器 / 手电 / 剪贴板 / 通知）全都要走那座桥。
     * 原先判据只看 ADB 开关（默认关），于是「只开了悬浮条、没开设备桥」的用户那里，
     * 桥根本没监听 —— 插件连不上，而它是静默失败的，用户只看到「悬浮窗不显示」，
     * 没有任何线索。功能之间的这种隐性依赖必须在判据里写明。
     *
     * <p>注意：放宽的只是「桥要不要起」，<b>不是权限</b>。Shizuku 绑定与 shell 执行仍然
     * 只在 ADB 开关打开时才做（见 onCreate）。
     */
    public static boolean needed(Context ctx) {
        if (isAdbEnabled(ctx)) return true;
        try {
            return OverlayController.enabled(ctx);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 按需启停。默认都关：不想用这些功能的人不会被后台服务拖慢。 */
    public static void apply(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, DeviceBridgeService.class);
        if (needed(app)) {
            try {
                app.startService(i);
            } catch (Throwable ignored) {
            }
        } else {
            try {
                app.stopService(i);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final String WATCH_CHANNEL = "dsh_adb_watch_channel";
    private static final int WATCH_NOTIF_ID = 3005;
    /** 常驻设备桥卡片（3006）已废弃：用户反馈打开 App 不应自动弹配对通知 */

    private NsdManager nsd;
    private NsdManager.DiscoveryListener pairListener;
    /** 手动开启无线调试提醒节流 */
    private volatile long lastManualNotifyAt = 0;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!needed(this)) {
            stopSelf();
            return;
        }
        running = true;
        current = this;
        try {
            new HttpShellService(this).start();
        } catch (Throwable ignored) {
        }
        // Shizuku 只在 ADB 开关打开时绑定：3090 桥本身还要给悬浮条与设备能力用，
        // 但 shell 执行权限不该因为「顺手开了悬浮条」就一起给出去
        if (!isAdbEnabled(this)) {
            return;
        }
        try {
            ShizukuShell.ensureBound(this);
        } catch (Throwable ignored) {
        }
        prewarmAdb();
        startPairWatcher();
        startKeepAlive(); // ADB 保活：自适应周期 + 网络/屏幕事件触发 + Doze 下 Alarm 兜底
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 用户主动开启的通道，被系统回收后应当恢复（否则 ADB 能力静默消失）
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        current = null;
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
        try {
            // 联动关闭 HTTP shell 桥（否则 3090 端口残留监听）
            HttpShellService hs = HttpShellService.instance();
            if (hs != null) hs.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (nsd != null && pairListener != null) {
                nsd.stopServiceDiscovery(pairListener);
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** ADB 配对环境后台预就绪（幂等，已装则秒回） */
    private void prewarmAdb() {
        try {
            final HarnessController c = HarnessController.get(this);
            if (c == null || !c.getProot().isInstalled()) return;
            new Thread(() -> {
                try {
                    AdbBridge.ensureReady(DeviceBridgeService.this, c.getProot());
                } catch (Throwable ignored) {
                }
            }, "dsha-adb-prewarm").start();
        } catch (Throwable ignored) {
        }
    }

    // ===================== ADB 保活（自适应看门狗 + 事件触发 + Doze 兜底） =====================

    private final android.os.Handler watchHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** 连续失败次数：决定下一次探测多久之后（越早失败越快重试，长期失败则降频省电） */
    private volatile int consecutiveFailures = 0;
    private volatile long lastOkAt = 0;
    /** 单飞：事件触发与周期触发可能同时到，探测本身要串行 */
    private final java.util.concurrent.atomic.AtomicBoolean probing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long lastKickAt = 0;
    /** 对外可读的连接状态（配置页/自检/诊断用） */
    public static volatile String adbState = "unknown";
    public static volatile String adbDetail = "";

    private static final long OK_INTERVAL_MS = 60_000L;
    /** 刚断开时的快速退避阶梯 */
    private static final long[] BACKOFF_MS = { 3_000L, 6_000L, 12_000L, 24_000L, 45_000L };
    private static final long LONG_FAIL_INTERVAL_MS = 120_000L;
    private static final long KICK_DEBOUNCE_MS = 1_500L;

    private android.net.ConnectivityManager.NetworkCallback netCallback;
    private android.content.BroadcastReceiver screenReceiver;

    /** 启动保活：周期探测 + 网络恢复/屏幕解锁即时触发 + AlarmManager 在 Doze 下兜底 */
    private void startKeepAlive() {
        watchHandler.postDelayed(periodicProbe, 15_000L);
        startNetworkWatcher();
        startScreenWatcher();
        AdbKeepAliveReceiver.schedule(this);
    }

    private final Runnable periodicProbe = new Runnable() {
        @Override
        public void run() {
            if (!running || !isAdbEnabled(DeviceBridgeService.this)) return;
            probeAsync("周期");
            watchHandler.postDelayed(this, nextDelayMs());
        }
    };

    private long nextDelayMs() {
        if (consecutiveFailures == 0) return OK_INTERVAL_MS;
        if (consecutiveFailures <= BACKOFF_MS.length) return BACKOFF_MS[consecutiveFailures - 1];
        return LONG_FAIL_INTERVAL_MS;
    }

    /** 外部事件触发一次立即探测（网络恢复、屏幕解锁、Alarm 唤醒、用户回到 App） */
    void kick(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastKickAt < KICK_DEBOUNCE_MS) return; // 多个事件同时到只跑一次
        lastKickAt = now;
        probeAsync(reason);
    }

    /** 供 App 内其它组件（配置页、Alarm 接收器）触发 */
    public static void kickNow(Context ctx, String reason) {
        DeviceBridgeService svc = current;
        if (svc != null) {
            svc.kick(reason);
        } else {
            apply(ctx); // 服务不在了：先按开关拉起来
        }
    }

    private void probeAsync(final String reason) {
        if (!probing.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                runProbe(reason);
            } catch (Throwable e) {
                android.util.Log.w("DSHA-ADB", "保活探测异常: " + e);
            } finally {
                probing.set(false);
            }
        }, "dsha-adb-watchdog").start();
    }

    /**
     * 一轮探测/自愈：
     *  1. 连接可用 → 记录 ok，清零失败计数
     *  2. 依赖缺失 → 触发 setup 自愈
     *  3. 掉线 → mDNS 重发现连接端口，用新端口重试
     *  4. 仍不行 → 无线调试可能被关：WRITE_SECURE_SETTINGS 直接开，其次 Shizuku
     *  5. 都失败 → 分类记录状态，低频通知用户
     */
    /** 距上次「完整握手验证」的时间。TCP 探活省掉了 toast，但不能永远只看 TCP ——
     *  配对可能被系统撤销而端口仍在监听，那样就是假健康。 */
    private static volatile long lastFullVerifyAt = 0L;
    private static final long FULL_VERIFY_INTERVAL_MS = 30 * 60 * 1000L;   // 30 分钟

    /** 读容器里记录的 ADB 连接端口（adb-shell.py 的同一个来源）。
     *  直接读 rootfs 文件而不进容器：探活要尽量轻，且不能有任何 ADB 侧动作。 */
    private int readConnectPort(HarnessController c) {
        try {
            java.io.File f = new java.io.File(c.getProot().getRootfsDir(),
                    "root/.dsh/adbkeys/connect_port");
            if (f.isFile()) {
                String t = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                int v = Integer.parseInt(t);
                if (v > 0 && v < 65536) return v;
            }
        } catch (Throwable ignored) {
        }
        return 5555;   // 与 adb-shell.py 的兜底一致
    }

    /** 纯 TCP 可达性检查：连上就断，不发任何 ADB 协议数据。
     *  这是整个改动的关键 —— 不完成 TLS 握手，框架层就不会记一次新设备连接。 */
    private boolean tcpReachable(int port, int timeoutMs) {
        if (port <= 0) return false;
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), timeoutMs);
            return sock.isConnected();
        } catch (Throwable e) {
            return false;
        }
    }

    private void runProbe(String reason) {
        // 3090 桥自愈：HarnessService 也会 new 一个 HttpShellService，谁抢到端口谁持有；
        // 它在停止 Web 时把桥关掉后，ADB 开关还开着，agent 的确认请求就会全部
        // fail-closed 被拒。这里补起来（start() 内部有跨实例互斥，重复调用安全）。
        try {
            if (HttpShellService.instance() == null) {
                new HttpShellService(this).start();
            }
        } catch (Throwable ignored) {
        }
        HarnessController c = HarnessController.get(this);
        if (c == null || !c.getProot().isInstalled()) {
            setAdbState("no_env", "环境未安装");
            return;
        }
        // ===== 无副作用探活（议题 #35）=====
        // adb-shell.py 是无状态客户端：每次调用都完整走一遍 AdbDeviceTls 握手。
        // 于是「每轮保活探测 = 一次全新的无线调试设备连接 = 一次系统级
        // 『已连接到无线调试』toast」。保活本身工作正常（failures=0），
        // 但用户几分钟就被弹一次，还以为无线调试关不掉。
        //
        // 改成两级：先在 App 进程里对记录的端口做一次 TCP 可达性检查
        // （框架层不产生「新设备连接」事件，因此没有 toast），通了就算健康；
        // 只有 TCP 不通才走完整握手去触发原有的自愈链路。
        //
        // 但 TCP 通 ≠ ADB 授权还在（配对可能被系统撤销），所以每
        // FULL_VERIFY_INTERVAL_MS 仍强制做一次完整验证，避免「假健康」
        // 一直掩盖真实掉线。
        boolean needFull = System.currentTimeMillis() - lastFullVerifyAt > FULL_VERIFY_INTERVAL_MS;
        if (!needFull && tcpReachable(readConnectPort(c), 1200)) {
            onProbeOk(reason);
            return;
        }
        String r = c.getProot().execAndRead("DSH_INTERNAL=" + HttpShellService.mintInternalTicket()
                + " python3 /root/.dsh/adb-shell.py id 2>&1 | head -3");
        if (r != null && r.contains("uid=")) lastFullVerifyAt = System.currentTimeMillis();
        if (r != null && r.contains("uid=")) {
            onProbeOk(reason);
            return;
        }
        if (r != null && r.contains("DEPS_MISSING")) {
            setAdbState("installing", "正在补装 ADB 依赖");
            AdbBridge.ensureReady(this, c.getProot());
            consecutiveFailures++;
            return;
        }
        setAdbState("reconnecting", "触发原因：" + reason);
        int connPort = discoverConnPortSync();
        if (connPort > 0) {
            saveConnectPort(connPort);
            String r2 = c.getProot().execAndRead(
                    "DSH_INTERNAL=" + HttpShellService.mintInternalTicket()
                            + " python3 /root/.dsh/adb-shell.py --port " + connPort + " id 2>&1 | head -3");
            if (r2 != null && r2.contains("uid=")) {
                android.util.Log.i("DSHA-ADB", "保活：已重连端口 " + connPort + "（" + reason + "）");
                onProbeOk("重连端口 " + connPort);
                return;
            }
            // 端口在、连不上 → 多半是配对信息失效（换过手机/清过数据）
            if (r2 != null && (r2.contains("Unauthorized") || r2.contains("unauthorized")
                    || r2.contains("认证") || r2.contains("AUTH"))) {
                consecutiveFailures++;
                setAdbState("need_pair", "配对已失效，需要重新配对");
                notifyAdbProblem("需要重新配对", "配对信息已失效，请到「配置」页点「ADB 无线配对」重新配一次");
                return;
            }
        }
        boolean opened = tryReopenWirelessDebug();
        if (opened) {
            // 死等 5 秒两头不讨好：adbd 常常 1 秒内就绪（白等 4 秒），
            // 而慢设备可能 8 秒才起来（等了还是失败）。改成轮询到端口出现为止。
            int p2 = -1;
            long deadline = System.currentTimeMillis() + 12_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                    break;
                }
                p2 = discoverConnPortSync();
                if (p2 > 0) break;
            }
            if (p2 > 0) {
                saveConnectPort(p2);
                String r3 = c.getProot().execAndRead(
                        "DSH_INTERNAL=" + HttpShellService.mintInternalTicket()
                                + " python3 /root/.dsh/adb-shell.py --port " + p2 + " id 2>&1 | head -1");
                if (r3 != null && r3.contains("uid=")) {
                    onProbeOk("自动重开无线调试后重连");
                    return;
                }
            }
            consecutiveFailures++;
            setAdbState("reconnecting", "已重开无线调试，等待 adbd 就绪");
            return;
        }
        consecutiveFailures++;
        setAdbState("need_manual", "无线调试似乎已关闭（失败 " + consecutiveFailures + " 次）");
        // 连续失败到一定次数才打扰用户：偶发一两次会自己好
        if (consecutiveFailures >= 3) {
            notifyAdbProblem("ADB 连接已断开",
                    "自动重连未成功。打开手机「开发者选项 → 无线调试」后会自动恢复");
        }
    }

    private void onProbeOk(String detail) {
        consecutiveFailures = 0;
        lastOkAt = System.currentTimeMillis();
        setAdbState("ok", detail);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(WATCH_NOTIF_ID); // 恢复了就把提醒收走
        } catch (Throwable ignored) {
        }
    }

    /** 尝试自动重开无线调试：先用 WRITE_SECURE_SETTINGS（无需 Shizuku），再退 Shizuku */
    private boolean tryReopenWirelessDebug() {
        try {
            boolean hasSecure = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
            if (hasSecure) {
                int cur = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
                if (cur != 1) {
                    Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                    android.util.Log.i("DSHA-ADB", "保活：WRITE_SECURE_SETTINGS 已开启无线调试");
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (ShizukuShell.isAvailable()) {
                String out = ShizukuShell.exec(
                        "settings put global adb_wifi_enabled 1 2>&1; adb tcpip 5555 2>&1");
                android.util.Log.i("DSHA-ADB", "保活：Shizuku 重开无线调试 → " + out);
                return out != null && !out.contains("[NO_") && !out.contains("ERROR");
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 状态同时落盘到 rootfs，容器内 cat /root/.dsh/adb-status 即可看，自检也读它 */
    private void setAdbState(String state, String detail) {
        adbState = state;
        adbDetail = detail == null ? "" : detail;
        try {
            HarnessController c = HarnessController.get(this);
            if (c == null || c.getProot() == null) return;
            java.io.File f = new java.io.File(c.getProot().getRootfsDir(), "root/.dsh/adb-status");
            if (f.getParentFile() != null && !f.getParentFile().isDirectory()
                    && !f.getParentFile().mkdirs()) {
                return;
            }
            String body = "state=" + state + "\n"
                    + "detail=" + adbDetail.replace("\n", " ") + "\n"
                    + "failures=" + consecutiveFailures + "\n"
                    + "last_ok=" + (lastOkAt > 0
                            ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                                    .format(new java.util.Date(lastOkAt))
                            : "never") + "\n"
                    + "updated=" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                            java.util.Locale.US).format(new java.util.Date()) + "\n";
            java.nio.file.Files.write(f.toPath(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 网络恢复立刻重连（不然要等下一个周期，WiFi 切换后能白等半分钟） */
    private void startNetworkWatcher() {
        try {
            final android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    kick("网络恢复");
                }

                @Override
                public void onLost(android.net.Network network) {
                    setAdbState("network_lost", "网络断开，等待恢复");
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Throwable e) {
            android.util.Log.w("DSHA-ADB", "网络监听注册失败: " + e);
        }
    }

    /** 屏幕点亮/解锁时探一次：用户开始用手机的时刻，正是最需要连接就绪的时刻 */
    private void startScreenWatcher() {
        try {
            screenReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    kick("屏幕点亮/解锁");
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenReceiver, f);
        } catch (Throwable e) {
            android.util.Log.w("DSHA-ADB", "屏幕广播注册失败: " + e);
        }
    }

    /** 同步发现 _adb-tls-connect 连接端口（0=没发现） */
    private int discoverConnPortSync() {
        final int[] port = new int[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            NsdManager nm = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nm == null) return 0;
            final NsdManager.DiscoveryListener[] holder = new NsdManager.DiscoveryListener[1];
            holder[0] = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onStartDiscoveryFailed(String t, int e) { done.countDown(); }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onServiceFound(NsdServiceInfo info) {
                    nm.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo s, int e) { done.countDown(); }
                        @Override public void onServiceResolved(NsdServiceInfo s) {
                            port[0] = s.getPort();
                            try { nm.stopServiceDiscovery(holder[0]); } catch (Throwable ignored) { }
                            done.countDown();
                        }
                    });
                }
                @Override public void onServiceLost(NsdServiceInfo info) { }
            };
            nm.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, holder[0]);
            done.await(4000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) { }
        return port[0];
    }

    /** 写连接端口到 rootfs（adb-shell.py 默认读取） */
    private void saveConnectPort(int port) {
        try {
            HarnessController c = HarnessController.get(this);
            if (c == null || c.getProot() == null) return;
            c.getProot().execAndRead("mkdir -p /root/.dsh/adbkeys && echo " + port + " > /root/.dsh/adbkeys/connect_port");
        } catch (Throwable ignored) { }
    }

    /** 通知用户 ADB 出了什么问题（10 分钟节流，恢复后自动收走） */
    private void notifyAdbProblem(String title, String text) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastManualNotifyAt < 600000) return; // 10 分钟一次
            lastManualNotifyAt = now;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        WATCH_CHANNEL, "ADB 连接提醒", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent app = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 25, app,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, WATCH_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launch)
                    .setContentTitle("🔌 " + title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            nm.notify(WATCH_NOTIF_ID, b.build());
        } catch (Throwable ignored) { }
    }

    /** 持续监听无线调试配对服务（弹窗打开时 adbd 会广播 _adb-tls-pairing） */
    private void startPairWatcher() {
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            pairListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    try {
                        nsd.resolveService(info, new NsdManager.ResolveListener() {
                            @Override
                            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            }

                            @Override
                            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                                int port = serviceInfo.getPort();
                                if (port > 0) {
                                    // 缓存真实 host：部分 ROM 配对服务只监听 WiFi 接口，127.0.0.1 连不上
                                    try {
                                        if (serviceInfo.getHost() != null) {
                                            String h = serviceInfo.getHost().getHostAddress();
                                            if (h != null) {
                                                if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
                                                pairHost = h;
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                    onPairServiceFound(port);
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                }
            };
            nsd.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, pairListener);
        } catch (Throwable ignored) {
        }
    }

    /** 配对弹窗出现：只缓存端口/主机供配对时秒级直用（不弹通知——
     *  用户反馈：配对通知只应在点击「ADB 无线配对」时出现，打开就弹会打扰）。
     *  配对入口：配置页按钮 → showAdbPairNotification()（3101 单条） */
    private void onPairServiceFound(int port) {
        pairPort = port;
    }
}
