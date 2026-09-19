package com.deepseekharness.app.backup;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * 在删除作业目录前封住新 worker，并等待已经登记的 worker 真正跑完 finally。
 * 登记发生在线程 start 之前，避免“刚判断空闲，线程才启动”的窗口。
 */
final class WorkerQuiescence {
    final class Lease implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            synchronized (WorkerQuiescence.this) {
                if (closed) return;
                closed = true;
                active--;
                WorkerQuiescence.this.notifyAll();
            }
        }
    }

    private boolean closed;
    private int active;

    synchronized Lease tryBegin() {
        if (closed) return null;
        active++;
        return new Lease();
    }

    synchronized void closeGate() { closed = true; }

    synchronized void reopen() {
        closed = false;
        notifyAll();
    }

    synchronized boolean isClosed() { return closed; }

    synchronized int active() { return active; }

    void awaitIdle(BackupControl control, long timeoutMillis) throws IOException {
        long timeoutNanos = Math.max(0, timeoutMillis) * 1_000_000L;
        long started = System.nanoTime();
        synchronized (this) {
            while (active > 0) {
                control.check();
                long remaining = timeoutNanos - (System.nanoTime() - started);
                if (remaining <= 0) throw new IOException("WORKER_QUIESCE_TIMEOUT");
                long waitMillis = Math.max(1, Math.min(100, remaining / 1_000_000L));
                try { wait(waitMillis); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("WORKER_QUIESCE_INTERRUPTED");
                }
            }
        }
    }
}
