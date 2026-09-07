package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BridgeLifecycleTest {
    @Test public void bindFailureIsNotReadyAndAllowsRetry() {
        BridgeLifecycle state = new BridgeLifecycle();
        long failed = state.beginStart();
        assertTrue(state.isStarting()); assertFalse(state.isReady());
        assertTrue(state.finish(failed)); assertFalse(state.isStarting()); assertFalse(state.isReady());
        long retry = state.beginStart(); assertTrue(retry > failed);
        assertTrue(state.publish(retry)); assertTrue(state.isReady()); assertFalse(state.isStarting());
    }

    @Test public void delayedOldBindAndCleanupCannotReplaceCurrentListener() {
        BridgeLifecycle state = new BridgeLifecycle();
        long old = state.beginStart(); state.finish(old);
        long current = state.beginStart();
        assertFalse(state.publish(old)); assertFalse(state.finish(old)); assertTrue(state.isStarting());
        assertTrue(state.publish(current));
        assertFalse(state.finish(old)); assertTrue(state.isReady()); assertEquals(-1, state.beginStart());
    }

    @Test public void acceptFailureWithdrawsReadyAndCanStartAgain() {
        BridgeLifecycle state = new BridgeLifecycle();
        long run = state.beginStart(); assertTrue(state.publish(run));
        assertTrue(state.finish(run)); assertFalse(state.isReady());
        assertFalse(state.publish(run)); assertTrue(state.beginStart() > run);
    }

    @Test public void concurrentServicesHaveOnlyOneStartingOwner() throws Exception {
        BridgeLifecycle state = new BridgeLifecycle();
        CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(12);
        AtomicInteger owners = new AtomicInteger();
        for (int i = 0; i < 12; i++) {
            Thread contender = new Thread(() -> {
                try { go.await(); if (state.beginStart() > 0) owners.incrementAndGet(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            });
            contender.setDaemon(true); contender.start();
        }
        go.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(1, owners.get());
        assertTrue(state.isStarting()); assertFalse(state.isReady());
    }
}
