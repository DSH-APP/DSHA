package com.deepseekharness.app.util;

import java.util.concurrent.TimeUnit;

/** 终止请求与已退出是两件事；清理期间保留中断状态，不能因中断提前交还任务所有权。 */
public final class ProcessTermination {
    private ProcessTermination() { }
    public static boolean exited(Process process) {
        try { process.exitValue(); return true; }
        catch (IllegalThreadStateException running) { return false; }
    }
    public static boolean awaitExit(Process process, long millis) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        boolean interrupted = Thread.interrupted();
        try {
            while (!exited(process)) {
                if (System.nanoTime() >= end) return false;
                try { Thread.sleep(10); } catch (InterruptedException e) { interrupted = true; }
            }
            return true;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    /** graceful 仅给具备明确清理协议的进程；force 也只能作用于同一本次进程。 */
    public static void stop(Process process, Runnable graceful, Runnable force, long graceMs, long forceMs) {
        if (exited(process)) return;
        RuntimeException signalFailure = null;
        if (graceful != null) {
            try { graceful.run(); } catch (RuntimeException e) { signalFailure = e; }
            if (awaitExit(process, graceMs)) return;
        }
        try { force.run(); } catch (RuntimeException e) { signalFailure = e; }
        if (!awaitExit(process, forceMs)) throw new IllegalStateException("本次进程仍未退出，不能开始下一任务", signalFailure);
    }
}
