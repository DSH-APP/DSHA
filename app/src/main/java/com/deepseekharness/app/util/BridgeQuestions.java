package com.deepseekharness.app.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 提问按请求保存答案与期限；超时、取消、旧按钮和延迟清理只能结束其所属请求。 */
public final class BridgeQuestions {
    public enum End { ANSWER, DISMISSED, TIMEOUT, BACKGROUND, UNAVAILABLE, STOPPED, INTERRUPTED }

    public static final class Request {
        public final long generation;
        private final long started, budget;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile End end;
        private volatile String answer = "";
        private Request(long generation, long started, long budget) {
            this.generation = generation;
            this.started = started;
            this.budget = budget;
        }
        public End end() { return end; }
        public String answer() { return answer; }
    }

    private final LongSupplier clock;
    private long generation;
    private Request active;
    public BridgeQuestions() { this(System::nanoTime); }
    BridgeQuestions(LongSupplier clock) { this.clock = clock; }

    public synchronized Request begin(long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("提问期限必须大于零"));
        if (active != null) return null;
        active = new Request(++generation, clock.getAsLong(), TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        return active;
    }

    public synchronized boolean pending(Request request) {
        if (active != request || request.end != null) return false;
        if (remaining(request) <= 0) complete(request, End.TIMEOUT, "");
        return request.end == null;
    }

    public synchronized boolean answer(Request request, String value) {
        if (!pending(request)) return false;
        complete(request, End.ANSWER, value);
        return true;
    }

    public synchronized void cancel(Request request, End why) {
        if (why == End.ANSWER) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("回答必须经 answer 提交"));
        if (active == request && request.end == null) complete(request, why, "");
    }

    public synchronized void stop() {
        if (active != null) cancel(active, End.STOPPED);
    }

    public End await(Request request) throws InterruptedException {
        while (pending(request)) {
            long left = remaining(request);
            if (left > 0) request.done.await(left, TimeUnit.NANOSECONDS);
        }
        return request.end;
    }

    /** 先结束自己的请求再释放入口；旧 finally 不能释放新请求的占位。 */
    public synchronized void release(Request request) {
        if (active != request) return;
        if (request.end == null) complete(request, End.UNAVAILABLE, "");
        active = null;
    }

    private long remaining(Request request) { return request.budget - (clock.getAsLong() - request.started); }
    private static void complete(Request request, End end, String answer) {
        request.answer = answer == null ? "" : answer;
        request.end = end;
        request.done.countDown();
    }
}
