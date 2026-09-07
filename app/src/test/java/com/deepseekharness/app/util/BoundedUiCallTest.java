package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** 只运行假工作函数，不使用 Android API，也不读取或写入任何剪贴板。 */
public class BoundedUiCallTest {
    private static final class Queue implements BoundedUiCall.Dispatcher {
        Runnable submitted;
        boolean removed;
        @Override public boolean post(Runnable task) { submitted = task; return true; }
        @Override public void remove(Runnable task) { assertSame(submitted, task); removed = true; }
    }
    private static BoundedUiCall.Dispatcher threaded() {
        return new BoundedUiCall.Dispatcher() {
            @Override public boolean post(Runnable task) {
                Thread thread = new Thread(task, "fake-ui"); thread.setDaemon(true); thread.start(); return true;
            }
            @Override public void remove(Runnable task) { }
        };
    }

    @Test public void queuedTimeoutCancelsWithoutExecutingEvenWhenDeliveryArrivesLate() {
        Queue queue = new Queue(); AtomicInteger effects = new AtomicInteger();
        long started = System.nanoTime();
        BoundedUiCall.Result<Integer> result = BoundedUiCall.call(queue, effects::incrementAndGet, 30);
        assertEquals(BoundedUiCall.Status.NOT_EXECUTED, result.status);
        assertTrue(queue.removed); queue.submitted.run();
        assertEquals(0, effects.get()); assertNull(result.value);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
    }

    @Test public void startedButBlockedWorkReturnsUnknownAndNeverClaimsItWasCancelled() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1), executed = new CountDownLatch(1);
        AtomicReference<BoundedUiCall.Result<String>> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(BoundedUiCall.call(threaded(), () -> {
            entered.countDown(); finish.await(); executed.countDown(); return "实际完成";
        }, 200)));
        caller.setDaemon(true); caller.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS)); caller.join(1500);
            assertFalse(caller.isAlive()); assertEquals(BoundedUiCall.Status.UNKNOWN, result.get().status);
            assertNull(result.get().value); assertEquals(1, executed.getCount());
        } finally { finish.countDown(); }
        assertTrue(executed.await(1, TimeUnit.SECONDS));
        assertEquals(BoundedUiCall.Status.UNKNOWN, result.get().status);
    }

    @Test public void successOnlyFollowsCompletedSideEffect() {
        AtomicInteger effects = new AtomicInteger();
        BoundedUiCall.Result<String> result = BoundedUiCall.call(threaded(), () -> {
            effects.incrementAndGet(); return "真实结果";
        }, 1000);
        assertEquals(1, effects.get()); assertEquals(BoundedUiCall.Status.SUCCESS, result.status);
        assertEquals("真实结果", result.value); assertNull(result.error);
    }

    @Test public void thrownOperationIsFailureInsteadOfSuccessfulEnqueue() {
        IllegalStateException denied = new IllegalStateException("fixture 操作被拒绝");
        BoundedUiCall.Result<String> result = BoundedUiCall.call(threaded(), () -> { throw denied; }, 1000);
        assertEquals(BoundedUiCall.Status.FAILED, result.status); assertSame(denied, result.error); assertNull(result.value);
    }

    @Test public void rejectedDispatchNeverExecutesWork() {
        AtomicInteger effects = new AtomicInteger();
        BoundedUiCall.Result<Integer> result = BoundedUiCall.call(new BoundedUiCall.Dispatcher() {
            @Override public boolean post(Runnable task) { return false; }
            @Override public void remove(Runnable task) { }
        }, effects::incrementAndGet, 1000);
        assertEquals(BoundedUiCall.Status.FAILED, result.status); assertEquals(0, effects.get());
    }

    @Test public void interruptedQueuedWaitCancelsAndPreservesInterruptFlag() {
        Queue queue = new Queue(); AtomicInteger effects = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            BoundedUiCall.Result<Integer> result = BoundedUiCall.call(queue, effects::incrementAndGet, 1000);
            assertEquals(BoundedUiCall.Status.NOT_EXECUTED, result.status); assertTrue(result.interrupted);
            assertTrue(Thread.currentThread().isInterrupted());
            queue.submitted.run(); assertEquals(0, effects.get());
        } finally { Thread.interrupted(); }
    }
}
