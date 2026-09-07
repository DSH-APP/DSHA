package com.deepseekharness.app.core;

import android.content.Context;
import android.os.PowerManager;

/** 原生工作独立持有 CPU 锁；省电释放 Web 空闲锁不会中断备份、插件或终端。 */
public final class RuntimeTasks implements AutoCloseable {
    private static Context context;
    private static final com.deepseekharness.app.util.RuntimeTaskRegistry tasks =
            new com.deepseekharness.app.util.RuntimeTaskRegistry();
    private static PowerManager.WakeLock lock;
    private static final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable renewal = new Runnable() {
        @Override public void run() {
            synchronized (RuntimeTasks.class) {
                if (tasks.count() > 0) { renew(); handler.postDelayed(this, 600_000); }
            }
        }
    };
    private boolean closed;
    private Process retainedProcess;
    private final com.deepseekharness.app.util.RuntimeTaskRegistry.Token token;
    private RuntimeTasks(com.deepseekharness.app.util.RuntimeTaskRegistry.Token token) { this.token = token; }
    public static synchronized void initialize(Context app) { context = app.getApplicationContext(); }
    public static synchronized RuntimeTasks begin() {
        return begin(false);
    }
    /** PTY/异步进程独立于创建线程的调用栈，不能作为维护的同步嵌套豁免。 */
    public static synchronized RuntimeTasks beginDetached() { return begin(true); }
    private static RuntimeTasks begin(boolean detached) {
        com.deepseekharness.app.util.RuntimeTaskRegistry.Token token = tasks.begin(detached);
        try {
            renew();
            if (tasks.count() == 1) handler.postDelayed(renewal, 600_000);
            return new RuntimeTasks(token);
        } catch (RuntimeException | Error failure) { token.close(); throw failure; }
    }
    public static synchronized boolean isBusy() { return tasks.count() > 0; }
    public static synchronized boolean hasOtherTasks() { return tasks.hasOtherTasks(); }
    public static synchronized com.deepseekharness.app.util.RuntimeTaskRegistry.Maintenance tryEnterMaintenance() {
        return tasks.tryEnterMaintenance();
    }
    /**
     * 返回/超时不等于子进程退出。复用本 token 保留异步寿命，退出监听接管 close。
     * 原调用方后续 close（包括 try-with-resources）不会提前解除保护；监听启动失败也保留 token。
     */
    public void retainUntilExit(Process process) {
        if (process == null) throw new IllegalArgumentException("待等待的进程不能为空");
        synchronized (RuntimeTasks.class) {
            if (closed) throw new IllegalStateException("已释放的工作锁不能重新保留进程");
            if (retainedProcess != null) {
                if (retainedProcess != process) throw new IllegalStateException("同一工作锁不能交给不同进程");
                return;
            }
            token.detach();
            retainedProcess = process;
        }
        try {
            Thread watcher = new Thread(() -> waitForRetainedExit(process), "dsha-runtime-exit");
            watcher.setDaemon(true);
            watcher.start();
        } catch (RuntimeException | Error failure) {
            // 故意保留 retainedProcess/token；不能因线程资源不足而把仍存活的进程当成已结束。
            throw new IllegalStateException("无法启动进程退出监听，已保留环境保护；请结束后台进程后重启 App", failure);
        }
    }
    private void waitForRetainedExit(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                try { process.exitValue(); break; }
                catch (IllegalThreadStateException running) { }
                try { Thread.sleep(1000); }
                catch (InterruptedException retry) { interrupted = true; }
            }
            synchronized (RuntimeTasks.class) {
                if (retainedProcess == process) { retainedProcess = null; closeLocked(); }
            }
        } catch (RuntimeException | Error failure) {
            // 无法确认退出时继续占用原 token；不扫描、终止或转移到任何其他进程。
            android.util.Log.w("DSHA", "无法确认后台进程退出，保留环境保护："
                    + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(failure)));
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    public static synchronized void renew() {
        if (tasks.count() == 0 || context == null) return;
        try {
            if (lock == null) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSHA:runtime-task");
                lock.setReferenceCounted(false);
            }
            lock.acquire(3_600_000);
        } catch (RuntimeException ignored) { }
    }
    @Override public void close() {
        synchronized (RuntimeTasks.class) {
            if (retainedProcess != null) return;
            closeLocked();
        }
    }
    private void closeLocked() {
        if (closed) return; closed = true; token.close();
        if (tasks.count() == 0) handler.removeCallbacks(renewal);
        if (tasks.count() == 0 && lock != null) {
            try { if (lock.isHeld()) lock.release(); } catch (RuntimeException ignored) { }
            lock = null;
        }
    }
}
