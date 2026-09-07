package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnvironmentTaskGateTest {
    @Test public void leaseAcquiredBeforeWorkerBlocksAllOtherTasks() throws Exception {
        try (EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("install")) {
            assertNotNull(lease); assertNull(EnvironmentTaskGate.tryAcquire("backup"));
            assertFalse(EnvironmentTaskGate.ownsCurrentThread());
            java.util.concurrent.atomic.AtomicBoolean owned = new java.util.concurrent.atomic.AtomicBoolean();
            Thread worker = new Thread(() -> { try { lease.run(() -> { owned.set(EnvironmentTaskGate.ownsCurrentThread()); return null; }); }
                catch (Exception e) { throw new AssertionError(e); } });
            worker.start(); worker.join();
            assertTrue(owned.get()); assertFalse(EnvironmentTaskGate.ownsCurrentThread());
            assertNull(EnvironmentTaskGate.tryAcquire("maintenance"));
        }
        assertFalse(EnvironmentTaskGate.isBusy());
    }
    @Test public void exceptionClearsThreadBindingAndFinallyReleasesLease() {
        EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("restore");
        try (lease) {
            assertThrows(java.io.IOException.class, () -> lease.run(() -> { throw new java.io.IOException("fixture"); }));
            assertFalse(EnvironmentTaskGate.ownsCurrentThread()); assertTrue(EnvironmentTaskGate.isBusy());
        }
        assertFalse(EnvironmentTaskGate.isBusy());
        assertThrows(IllegalStateException.class, () -> lease.run(() -> null));
    }
    @Test public void cannotReleaseOrReuseWhileWorkerRunning() throws Exception {
        try (EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("backup")) {
            lease.run(() -> {
                assertThrows(IllegalStateException.class, lease::close);
                assertThrows(IllegalStateException.class, () -> lease.run(() -> null));
                assertTrue(EnvironmentTaskGate.ownsCurrentThread()); return null;
            });
        }
    }
    @Test public void oneAtomicWinnerAmongDifferentTaskTypes() throws Exception {
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<EnvironmentTaskGate.Lease> winner = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[20];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> { try { go.await(); EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("task");
                if (lease != null) { count.incrementAndGet(); winner.set(lease); } } catch (InterruptedException e) { throw new AssertionError(e); } });
            threads[i].start();
        }
        go.countDown(); for (Thread thread : threads) thread.join();
        try (EnvironmentTaskGate.Lease lease = winner.get()) { assertEquals(1, count.get()); }
        assertFalse(EnvironmentTaskGate.isBusy());
    }
}
