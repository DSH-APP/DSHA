package com.deepseekharness.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import com.deepseekharness.app.util.SensitiveData;
import rikka.shizuku.Shizuku;

/** Provider 接收服务 Binder，Application 建立监听；不依赖 ADB 开关或 Ubuntu 环境。 */
public final class ShizukuShell {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static volatile Context context;
    private static volatile IShellService service;
    private static volatile ServiceConnection connection;
    private static volatile String failure = "";
    private static Shizuku.UserServiceArgs arguments;
    private static boolean listening;
    private static int requestCode = 9527;
    private ShizukuShell() { }

    public static void init(Context ctx) {
        if (ctx != null) context = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (listening || context == null) return;
            listening = true;
        }
        try {
            Shizuku.addBinderDeadListener(() -> MAIN.post(() -> disconnect(com.deepseekharness.app.util.UiText.text("Shizuku 服务已停止"), false)));
            Shizuku.addBinderReceivedListenerSticky(() -> MAIN.post(() -> ensureBound(context)));
        } catch (Throwable e) {
            synchronized (LOCK) { listening = false; }
            fail(e);
        }
    }
    public static boolean isInstalled(Context ctx) {
        return isAvailable() || ShizukuManagerCompat.manager(ctx) != null;
    }
    public static boolean isAvailable() {
        try { return Shizuku.pingBinder(); } catch (Throwable e) { return false; }
    }
    public static boolean hasPermission() {
        try { return isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable e) { return false; }
    }
    public static boolean isReady() {
        IShellService current = service;
        return hasPermission() && current != null && current.asBinder().pingBinder();
    }
    public static String status() {
        return "binder=" + isAvailable() + ",permission=" + hasPermission() + ",bound=" + isReady()
                + ",binding=" + (connection != null && service == null) + (failure.isEmpty() ? "" : ",error=" + failure);
    }
    public static String userStatus(Context ctx) {
        if (!isAvailable()) return isInstalled(ctx)
                ? com.deepseekharness.app.util.UiText.text("管理器已安装，服务尚未连接。点击授权重新连接；若仍未连接，请启动管理器服务，并检查隐藏模式是否允许 DSHA。")
                : com.deepseekharness.app.util.UiText.text("尚未安装 Shizuku。安装并启动后可在这里授权。");
        if (!hasPermission()) return com.deepseekharness.app.util.UiText.text("服务已连接，尚未授权 DSHA。点击下方授权。");
        if (isReady()) return com.deepseekharness.app.util.UiText.text("已授权 · 设备 Shell 已连接，可直接使用，无需 ADB 配对");
        if (!failure.isEmpty()) return com.deepseekharness.app.util.UiText.text("已授权，连接失败：") + failure + com.deepseekharness.app.util.UiText.text("。点击下方重试。");
        return com.deepseekharness.app.util.UiText.text("已授权，正在连接设备 Shell…");
    }
    public static void requestPermission(Shizuku.OnRequestPermissionResultListener callback) {
        MAIN.post(() -> {
            if (hasPermission()) { ensureBound(context); if (callback != null) callback.onRequestPermissionResult(0, PackageManager.PERMISSION_GRANTED); return; }
            final int code = ++requestCode;
            final Shizuku.OnRequestPermissionResultListener[] holder = new Shizuku.OnRequestPermissionResultListener[1];
            final boolean[] completed = {false};
            holder[0] = (received, result) -> {
                if (received != code || completed[0]) return;
                completed[0] = true;
                Shizuku.removeRequestPermissionResultListener(holder[0]);
                if (result == PackageManager.PERMISSION_GRANTED) ensureBound(context);
                if (callback != null) callback.onRequestPermissionResult(code, result);
            };
            try {
                Shizuku.addRequestPermissionResultListener(holder[0]);
                Shizuku.requestPermission(code);
                MAIN.postDelayed(() -> {
                    if (!completed[0]) { failure = com.deepseekharness.app.util.UiText.text("授权请求尚未完成，请重试"); holder[0].onRequestPermissionResult(code, PackageManager.PERMISSION_DENIED); }
                }, 60_000);
            } catch (Throwable e) {
                fail(e);
                holder[0].onRequestPermissionResult(code, PackageManager.PERMISSION_DENIED);
            }
        });
    }
    public static void ensureBound(Context ctx) {
        init(ctx);
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> ensureBound(context)); return; }
        if (context == null) return;
        if (!hasPermission()) { if (connection != null) disconnect("", true); return; }
        if (isReady()) return;
        if (service != null) disconnect("", true);
        if (connection != null) return;
        failure = "";
        arguments = new Shizuku.UserServiceArgs(new ComponentName(context, ShellService.class))
                .processNameSuffix("shizuku").tag("dsha-device-shell")
                .daemon(false).version(BuildConfig.VERSION_CODE);
        ServiceConnection pending = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (connection != this) return;
                try {
                    if (binder == null || !binder.pingBinder()) throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("设备 Shell 返回了失效的 Binder"));
                    binder.linkToDeath(() -> MAIN.post(() -> lost(this, com.deepseekharness.app.util.UiText.text("设备 Shell 已退出"))), 0);
                    synchronized (LOCK) { service = IShellService.Stub.asInterface(binder); failure = ""; LOCK.notifyAll(); }
                } catch (Throwable e) { fail(e); lost(this, failure); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { lost(this, com.deepseekharness.app.util.UiText.text("设备 Shell 连接断开")); }
            @Override public void onBindingDied(ComponentName name) { lost(this, com.deepseekharness.app.util.UiText.text("设备 Shell 绑定已失效")); }
            @Override public void onNullBinding(ComponentName name) { lost(this, com.deepseekharness.app.util.UiText.text("设备 Shell 未返回 Binder")); }
        };
        connection = pending;
        try {
            Shizuku.bindUserService(arguments, pending);
            MAIN.postDelayed(() -> {
                if (connection == pending && service == null) disconnect(com.deepseekharness.app.util.UiText.text("设备 Shell 连接超时"), true);
            }, 15_000);
        } catch (Throwable e) { fail(e); disconnect(failure, true); }
    }
    private static void lost(ServiceConnection expected, String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> lost(expected, message)); return; }
        if (connection != expected) return;
        disconnect(message, true);
        MAIN.postDelayed(() -> ensureBound(context), 4000);
    }
    private static void disconnect(String message, boolean unbind) {
        ServiceConnection old = connection;
        Shizuku.UserServiceArgs oldArguments = arguments;
        synchronized (LOCK) { connection = null; service = null; failure = message; LOCK.notifyAll(); }
        if (unbind && old != null && oldArguments != null) {
            try { Shizuku.unbindUserService(oldArguments, old, false); } catch (Throwable ignored) { }
        }
    }
    private static void fail(Throwable error) {
        failure = SensitiveData.redact(error.getClass().getSimpleName() + ": " + error.getMessage());
        android.util.Log.w("ShizukuShell", failure);
    }
    /** 只在发送前等待绑定；已经发送的命令失败后不自动重放。 */
    public static boolean awaitReady(Context ctx, long timeoutMs) {
        ensureBound(ctx);
        if (Looper.myLooper() == Looper.getMainLooper()) return isReady();
        long until = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (LOCK) {
            while (hasPermission() && !isReady()) {
                long remaining = until - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try { LOCK.wait(Math.min(remaining, 250)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return isReady();
    }
    public static String exec(String command) {
        if (!awaitReady(context, 8000)) return "[SHIZUKU_SERVICE_NOT_READY] " + status() + "\n[EXIT=124]";
        IShellService current = service;
        try { return current.exec(command); }
        catch (Throwable e) {
            fail(e);
            return com.deepseekharness.app.util.UiText.text("[EXECUTION_UNKNOWN] 设备命令结果未确认，不会自动重试：") + failure + "\n[EXIT=125]";
        }
    }
}
