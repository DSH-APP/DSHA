package com.deepseekharness.app.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 有界等待主线程工作的真实结果；只取消尚未开始的工作，不把在途副作用误报为未执行。 */
public final class BoundedUiCall {
    private BoundedUiCall() { }
    public interface Dispatcher {
        /** 必须入队，不应在调用线程同步执行。 */
        boolean post(Runnable task);
        void remove(Runnable task);
    }
    public enum Status { SUCCESS, FAILED, NOT_EXECUTED, UNKNOWN }
    public static final class Result<T> {
        public final Status status;
        public final T value;
        public final Throwable error;
        public final boolean interrupted;
        private Result(Status status, T value, Throwable error, boolean interrupted) {
            this.status = status; this.value = value; this.error = error; this.interrupted = interrupted;
        }
    }

    public static <T> Result<T> call(Dispatcher dispatcher, Callable<T> work, long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("主线程等待期限必须大于零"));
        long started = System.nanoTime();
        Task<T> task = new Task<>(work, started, TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        try {
            if (!dispatcher.post(task)) throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("主线程未接受操作"));
        } catch (RuntimeException error) {
            synchronized (task) { task.cancelled = true; }
            return new Result<>(Status.FAILED, null, error, false);
        }
        boolean interrupted = false;
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
            if (remaining > 0) task.done.await(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) { interrupted = true; }
        Result<T> result;
        synchronized (task) {
            if (task.finished) result = new Result<>(task.error == null ? Status.SUCCESS : Status.FAILED,
                    task.value, task.error, interrupted);
            else if (!task.started) {
                task.cancelled = true;
                result = new Result<>(Status.NOT_EXECUTED, null, null, interrupted);
            } else result = new Result<>(Status.UNKNOWN, null, null, interrupted);
        }
        if (result.status == Status.NOT_EXECUTED) {
            try { dispatcher.remove(task); } catch (RuntimeException ignored) { }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return result;
    }

    private static final class Task<T> implements Runnable {
        private final Callable<T> work;
        private final long submitted, budget;
        private final CountDownLatch done = new CountDownLatch(1);
        private boolean started, finished, cancelled;
        private T value;
        private Throwable error;
        Task(Callable<T> work, long submitted, long budget) { this.work = work; this.submitted = submitted; this.budget = budget; }
        @Override public void run() {
            synchronized (this) {
                if (cancelled || started) return;
                if (System.nanoTime() - submitted >= budget) { cancelled = true; done.countDown(); return; }
                started = true;
            }
            T outcome = null; Throwable failure = null;
            try { outcome = work.call(); } catch (Throwable problem) { failure = problem; }
            synchronized (this) {
                value = outcome; error = failure; finished = true;
                done.countDown();
            }
        }
    }
}
