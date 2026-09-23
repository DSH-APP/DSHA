package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class RuntimeTaskRegistryTest {
    @Test public void snapshotsPreserveTaskIdentityAndCannotReleaseProtection() {
        RuntimeTaskRegistry registry=new RuntimeTaskRegistry();
        RuntimeTaskRegistry.Token token=registry.begin(true,"插件管理");token.describe("同步列表");
        var rows=registry.snapshot();assertEquals(1,rows.size());assertEquals("插件管理",rows.get(0).kind);
        assertEquals("同步列表",rows.get(0).detail);assertTrue(rows.get(0).elapsedMillis>=0);
        assertThrows(UnsupportedOperationException.class,rows::clear);assertNull(registry.tryEnterMaintenance());
        long id=rows.get(0).id;token.detach();assertEquals(id,registry.snapshot().get(0).id);
        token.close();token.describe("不能复活");assertTrue(registry.snapshot().isEmpty());
        try(var next=registry.begin(true,"终端")){assertTrue(registry.snapshot().get(0).id>id);}
        assertEquals("同步列表",rows.get(0).detail);
    }
    @Test public void detachConvertsOriginalTokenWithoutChangingCount() {
        RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
        try (RuntimeTaskRegistry.Token caller = registry.begin(false); RuntimeTaskRegistry.Token process = registry.begin(false)) {
            assertEquals(2, registry.count()); assertFalse(registry.hasOtherTasks());
            process.detach(); process.detach();
            assertEquals(2, registry.count()); assertTrue(registry.hasOtherTasks());
            assertNull(registry.tryEnterMaintenance());
            process.close();
            assertEquals(1, registry.count()); assertFalse(registry.hasOtherTasks());
        }
    }
    @Test public void detachDoesNotReleaseExistingMaintenanceFence() throws Exception {
        RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
        try (RuntimeTaskRegistry.Token process = registry.begin(false)) {
            try (RuntimeTaskRegistry.Maintenance maintenance = registry.tryEnterMaintenance()) {
                assertNotNull(maintenance); process.detach(); assertEquals(1, registry.count());
                AtomicReference<Throwable> rejected = new AtomicReference<>();
                Thread outsider = new Thread(() -> { try { registry.begin(false); } catch (Throwable e) { rejected.set(e); } });
                outsider.start(); outsider.join();
                assertTrue(rejected.get() instanceof IllegalStateException);
                assertThrows(IllegalStateException.class, () -> registry.begin(true));
            }
            assertTrue(registry.hasOtherTasks()); assertNull(registry.tryEnterMaintenance());
        }
        try (RuntimeTaskRegistry.Maintenance next = registry.tryEnterMaintenance()) { assertNotNull(next); }
    }
    @Test public void detachAfterCloseDoesNotResurrectToken() {
        RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
        RuntimeTaskRegistry.Token token = registry.begin(false); token.close(); token.detach(); token.close();
        assertEquals(0, registry.count()); assertFalse(registry.hasOtherTasks());
    }
    @Test public void detachAndCrossThreadCloseAreAtomic() throws Exception {
        for (int i = 0; i < 40; i++) {
            RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
            RuntimeTaskRegistry.Token token = registry.begin(false);
            CountDownLatch go = new CountDownLatch(1);
            Thread watcher = new Thread(() -> { try { go.await(); token.close(); } catch (InterruptedException e) { throw new AssertionError(e); } });
            watcher.start(); go.countDown(); token.detach(); watcher.join();
            assertEquals(0, registry.count());
        }
    }
    @Test public void sameThreadSynchronousScopesRemainOwnedAfterMaintenance() {
        RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
        try (RuntimeTaskRegistry.Token outer = registry.begin(false); RuntimeTaskRegistry.Token nested = registry.begin(false)) {
            assertFalse(registry.hasOtherTasks());
            try (RuntimeTaskRegistry.Maintenance maintenance = registry.tryEnterMaintenance()) {
                assertNotNull(maintenance);
                try (RuntimeTaskRegistry.Token own = registry.begin(false)) { assertEquals(3, registry.count()); }
            }
            assertEquals(2, registry.count());
        }
        assertEquals(0, registry.count());
    }
    @Test public void longLivedTaskCreatedOnSameThreadIsNotANestedScope() {
        RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
        try (RuntimeTaskRegistry.Token terminal = registry.begin(true)) {
            assertTrue(registry.hasOtherTasks()); assertNull(registry.tryEnterMaintenance()); assertEquals(1, registry.count());
        }
        try (RuntimeTaskRegistry.Maintenance retry = registry.tryEnterMaintenance()) { assertNotNull(retry); }
    }
    @Test public void tokenCanBeClosedByExitWatcherWithoutTouchingOthers() throws Exception {
        RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
        try (RuntimeTaskRegistry.Token outer = registry.begin(false)) {
            RuntimeTaskRegistry.Token terminal = registry.begin(true);
            Thread watcher = new Thread(() -> { terminal.close(); terminal.close(); }); watcher.start(); watcher.join();
            assertEquals(1, registry.count()); assertFalse(registry.hasOtherTasks());
            try (RuntimeTaskRegistry.Maintenance maintenance = registry.tryEnterMaintenance()) { assertNotNull(maintenance); }
        }
    }
    @Test public void oldOtherThreadTaskBlocksMaintenanceWithoutBeingClosed() throws Exception {
        RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
        CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try (RuntimeTaskRegistry.Token work = registry.begin(false)) { ready.countDown(); release.await(); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        }); worker.start();
        try { assertTrue(ready.await(2, TimeUnit.SECONDS)); assertNull(registry.tryEnterMaintenance()); assertEquals(1, registry.count()); }
        finally { release.countDown(); worker.join(); }
        assertEquals(0, registry.count());
    }
    @Test public void maintenanceAtomicallyPreventsNewForeignAndDetachedTasks() throws Exception {
        RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
        try (RuntimeTaskRegistry.Maintenance maintenance = registry.tryEnterMaintenance()) {
            assertNotNull(maintenance);
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            Thread worker = new Thread(() -> { try { registry.begin(false); } catch (Throwable e) { rejected.set(e); } });
            worker.start(); worker.join();
            assertTrue(rejected.get() instanceof IllegalStateException);
            assertThrows(IllegalStateException.class, () -> registry.begin(true));
            assertEquals(0, registry.count());
        }
        try (RuntimeTaskRegistry.Token later = registry.begin(true)) { assertEquals(1, registry.count()); }
    }
    @Test public void failingMaintenanceReleasesFenceButKeepsCallerToken() {
        RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
        try (RuntimeTaskRegistry.Token caller = registry.begin(false)) {
            assertThrows(IllegalStateException.class, () -> {
                try (RuntimeTaskRegistry.Maintenance maintenance = registry.tryEnterMaintenance()) { throw new IllegalStateException("fixture"); }
            });
            assertEquals(1, registry.count());
            try (RuntimeTaskRegistry.Token terminal = registry.begin(true)) { assertEquals(2, registry.count()); }
        }
    }
    @Test public void racingTaskAndMaintenanceCannotBothEnter() throws Exception {
        for (int repeat = 0; repeat < 40; repeat++) {
            RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
            CountDownLatch go = new CountDownLatch(1), attempted = new CountDownLatch(2), release = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            Thread task = new Thread(() -> {
                RuntimeTaskRegistry.Token token = null;
                try { go.await(); token = registry.begin(true); winners.incrementAndGet(); }
                catch (IllegalStateException expected) { }
                catch (InterruptedException e) { throw new AssertionError(e); }
                finally { attempted.countDown(); try { release.await(); } catch (InterruptedException ignored) { }
                    if (token != null) token.close(); }
            });
            Thread maintenance = new Thread(() -> {
                RuntimeTaskRegistry.Maintenance fence = null;
                try { go.await(); fence = registry.tryEnterMaintenance(); if (fence != null) winners.incrementAndGet(); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                finally { attempted.countDown(); try { release.await(); } catch (InterruptedException ignored) { }
                    if (fence != null) fence.close(); }
            });
            task.start(); maintenance.start(); go.countDown();
            try { assertTrue(attempted.await(2, TimeUnit.SECONDS)); assertEquals(1, winners.get()); }
            finally { release.countDown(); task.join(); maintenance.join(); }
            assertEquals(0, registry.count());
        }
    }
}
