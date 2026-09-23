package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackupTaskStateTest {
    @Test public void previewKeepsGlobalSlotAndOldPageCannotConfirmAnotherTask() {
        BackupTaskState state = new BackupTaskState();
        long first = state.start("restore");
        state.update(first, BackupTaskState.Status.PREVIEW, "preview");
        assertTrue(state.busy()); assertEquals(-1, state.start("maintenance"));
        assertFalse(state.confirm(first - 1)); assertTrue(state.confirm(first));
        assertFalse(state.confirm(first));
        state.update(first, BackupTaskState.Status.SUCCEEDED, "done");
        long next = state.start("maintenance");
        state.update(next, BackupTaskState.Status.PREVIEW, "next");
        assertFalse(state.confirm(first)); assertTrue(state.confirm(next));
    }
    @Test public void processDeathNeverReplaysWorkOrPretendsSuccess() {
        for (BackupTaskState.Status before : new BackupTaskState.Status[]{BackupTaskState.Status.RUNNING, BackupTaskState.Status.PREVIEW}) {
            BackupTaskState state = new BackupTaskState();
            state.restore(42, "恢复", before, "正在恢复");
            assertFalse(state.busy()); assertEquals(BackupTaskState.Status.INTERRUPTED, state.snapshot().status);
            assertFalse(state.confirm(42)); assertTrue(state.snapshot().detail.contains("未自动重试"));
        }
    }
    @Test public void resultSurvivesNewUiAndCompletedTaskCannotBeOverwrittenByOldCallback() {
        BackupTaskState state = new BackupTaskState();
        long id = state.start("备份"); state.update(id, BackupTaskState.Status.SUCCEEDED, "saved");
        state.update(id, BackupTaskState.Status.FAILED, "late");
        BackupTaskState.Snapshot s = state.snapshot();
        BackupTaskState newProcess = new BackupTaskState();
        newProcess.restore(s.id, s.kind, s.status, s.detail);
        assertEquals("saved", newProcess.snapshot().detail);
        assertEquals(BackupTaskState.Status.SUCCEEDED, newProcess.snapshot().status);
        newProcess.reset();
        assertEquals(0, newProcess.snapshot().id);
        assertEquals(BackupTaskState.Status.IDLE, newProcess.snapshot().status);
        assertEquals("", newProcess.snapshot().detail);
    }
    @Test public void simultaneousStartsHaveExactlyOneOwner() throws Exception {
        BackupTaskState state = new BackupTaskState();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[24];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> { try { go.await(); if (state.start("test") >= 0) winners.incrementAndGet(); }
                catch (InterruptedException e) { throw new AssertionError(e); } }); threads[i].start();
        }
        go.countDown(); for (Thread thread : threads) thread.join();
        assertEquals(1, winners.get());
    }
}
