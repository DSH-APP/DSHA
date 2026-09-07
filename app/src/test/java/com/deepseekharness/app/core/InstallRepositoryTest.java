package com.deepseekharness.app.core;

import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.InstallTask;
import com.deepseekharness.app.util.InstallProcess;
import org.junit.Test;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** 直接覆盖仓库的真实排队/收尾路径，不依赖设备或 Android 主线程。 */
public class InstallRepositoryTest {
    private static final class Queue implements Executor, AutoCloseable {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean reject;
        @Override public void execute(Runnable runnable) {
            if (reject) throw new RejectedExecutionException("fixture 拒绝排队");
            tasks.add(runnable);
        }
        void runNext() { tasks.remove().run(); }
        @Override public void close() { while (!tasks.isEmpty()) runNext(); }
    }
    private InstallRepository healthy(Queue queue) {
        return new InstallRepository(queue, () -> false, (task, repair, step) -> task.finish(InstallTask.Outcome.SUCCEEDED, "完成"));
    }
    @Test public void holdsGlobalLeaseBeforeWorkerAndBindsItOnWorker() throws Exception {
        try (Queue queue = new Queue()) {
            AtomicBoolean bound = new AtomicBoolean();
            InstallRepository repository = new InstallRepository(queue, () -> false, (task, repair, step) -> {
                bound.set(EnvironmentTaskGate.ownsCurrentThread());
                task.finish(InstallTask.Outcome.SUCCEEDED, "完成");
                assertNull("结果收尾期间不能插入维护", EnvironmentTaskGate.tryAcquire("维护"));
            });
            assertTrue(repository.start(false, 0)); assertTrue(repository.snapshot().busy());
            assertTrue(EnvironmentTaskGate.isBusy()); assertFalse(EnvironmentTaskGate.ownsCurrentThread());
            assertNull(EnvironmentTaskGate.tryAcquire("备份")); assertFalse(repository.start(true, 0));
            Thread worker = new Thread(queue::runNext); worker.start(); worker.join();
            assertTrue(bound.get()); assertFalse(EnvironmentTaskGate.isBusy());
            assertEquals(InstallTask.Outcome.SUCCEEDED, repository.snapshot().outcome);
        }
    }
    @Test public void otherLeaseRejectsBeforePublishingOrReplacingResults() {
        try (Queue queue = new Queue()) {
            InstallRepository repository = healthy(queue);
            assertTrue(repository.start(false, 0)); queue.runNext();
            InstallTask.Snapshot previous = repository.snapshot();
            try (EnvironmentTaskGate.Lease maintenance = EnvironmentTaskGate.tryAcquire("恢复预览")) {
                assertNotNull(maintenance); assertFalse(repository.start(true, 0));
                assertEquals(previous.revision, repository.snapshot().revision);
                assertEquals(previous.log, repository.snapshot().log); assertTrue(queue.tasks.isEmpty());
            }
        }
    }
    @Test public void pendingMaintenanceRejectsAndReleasesReservation() {
        try (Queue queue = new Queue()) {
            InstallRepository repository = new InstallRepository(queue, () -> {
                assertTrue("必须在全局锁内检查磁盘维护状态", EnvironmentTaskGate.isBusy()); return true;
            }, (task, repair, step) -> fail("未完成维护时不能进入安装"));
            assertFalse(repository.start(false, 0)); assertFalse(EnvironmentTaskGate.isBusy());
            assertEquals(InstallTask.Outcome.IDLE, repository.snapshot().outcome); assertTrue(queue.tasks.isEmpty());
        }
    }
    @Test public void admissionExceptionAlsoReleasesLease() {
        try (Queue queue = new Queue()) {
            InstallRepository repository = new InstallRepository(queue, () -> { throw new IllegalStateException("fixture"); },
                    (task, repair, step) -> fail("不应执行"));
            assertFalse(repository.start(false, 0)); assertFalse(EnvironmentTaskGate.isBusy());
            assertEquals(InstallTask.Outcome.IDLE, repository.snapshot().outcome);
        }
    }
    @Test public void enqueueFailureReleasesLeaseAndAllowsRetry() {
        try (Queue queue = new Queue()) {
            InstallRepository repository = healthy(queue); queue.reject = true;
            assertFalse(repository.start(false, 0)); assertFalse(EnvironmentTaskGate.isBusy());
            assertEquals(InstallTask.Outcome.FAILED, repository.snapshot().outcome);
            queue.reject = false; assertTrue(repository.start(true, 0)); queue.runNext();
            assertEquals(InstallTask.Outcome.SUCCEEDED, repository.snapshot().outcome); assertFalse(EnvironmentTaskGate.isBusy());
        }
    }
    @Test public void workerFailureIsRedactedAndReleasesAllOwnership() {
        try (Queue queue = new Queue()) {
            InstallRepository repository = new InstallRepository(queue, () -> false,
                    (task, repair, step) -> { throw new IllegalStateException("token=fixture-secret 失败"); });
            assertTrue(repository.start(true, 0)); queue.runNext();
            assertEquals(InstallTask.Outcome.FAILED, repository.snapshot().outcome);
            assertFalse(repository.snapshot().log.contains("fixture-secret"));
            assertFalse(EnvironmentTaskGate.isBusy()); assertFalse(EnvironmentTaskGate.ownsCurrentThread());
            assertTrue(repository.start(false, 0)); queue.runNext();
        }
    }
    @Test public void threadCreationErrorCannotLeaveRunningStateOrLease() {
        InstallRepository repository = new InstallRepository(command -> { throw new AssertionError("fixture 线程创建失败"); },
                () -> false, (task, repair, step) -> fail("不应执行"));
        assertFalse(repository.start(false, 0)); assertFalse(EnvironmentTaskGate.isBusy());
        assertEquals(InstallTask.Outcome.FAILED, repository.snapshot().outcome);
        assertFalse(repository.snapshot().busy());
    }
    @Test public void cancellationBeforeWorkerStillCleansLeaseAndState() {
        try (Queue queue = new Queue()) {
            InstallRepository repository = new InstallRepository(queue, () -> false,
                    (task, repair, step) -> task.checkCancelled());
            assertTrue(repository.start(false, 0)); assertTrue(repository.cancel());
            assertTrue(EnvironmentTaskGate.isBusy()); queue.runNext();
            assertEquals(InstallTask.Outcome.CANCELLED, repository.snapshot().outcome);
            assertFalse(EnvironmentTaskGate.isBusy()); assertFalse(repository.snapshot().busy());
        }
    }
    @Test public void missingCompletionCannotLeakRunningStateOrLease() {
        try (Queue queue = new Queue()) {
            InstallRepository repository = new InstallRepository(queue, () -> false, (task, repair, step) -> { });
            assertTrue(repository.start(false, 0)); queue.runNext();
            assertEquals(InstallTask.Outcome.FAILED, repository.snapshot().outcome); assertFalse(EnvironmentTaskGate.isBusy());
            assertTrue(repository.start(false, 0)); queue.runNext();
        }
    }
    @Test public void invalidSelectionDoesNotReserveGlobalLease() {
        try (Queue queue = new Queue()) {
            InstallRepository repository = healthy(queue);
            assertThrows(IllegalArgumentException.class, () -> repository.start(false, 7));
            assertFalse(EnvironmentTaskGate.isBusy()); assertEquals(InstallTask.Outcome.IDLE, repository.snapshot().outcome);
        }
    }
    @Test public void unreapedProcessKeepsGlobalLeaseUntilItActuallyExits() throws Exception {
        AtomicBoolean alive = new AtomicBoolean(true);
        Process process = new Process() {
            @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
            @Override public java.io.InputStream getErrorStream() { return getInputStream(); }
            @Override public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
            @Override public int waitFor() { throw new AssertionError("不应无限 waitFor"); }
            @Override public int exitValue() { if (alive.get()) throw new IllegalThreadStateException(); return 0; }
            @Override public void destroy() { }
        };
        try (Queue queue = new Queue()) {
            InstallRepository repository = new InstallRepository(queue, () -> false, (task, repair, step) -> {
                throw new InstallProcess.CleanupFailure(process, Process::destroy, null);
            });
            assertTrue(repository.start(false, 0));
            Thread worker = new Thread(queue::runNext); worker.start();
            try {
                long end = System.nanoTime() + 2_000_000_000L;
                while (!repository.snapshot().phase.contains("继续回收") && System.nanoTime() < end) Thread.sleep(10);
                assertTrue(repository.snapshot().phase.contains("继续回收"));
                assertTrue(repository.snapshot().busy()); assertTrue(EnvironmentTaskGate.isBusy());
                assertNull(EnvironmentTaskGate.tryAcquire("维护")); assertFalse(repository.start(true, 0));
            } finally { alive.set(false); worker.join(2000); }
            assertFalse(worker.isAlive()); assertFalse(EnvironmentTaskGate.isBusy());
            assertEquals(InstallTask.Outcome.FAILED, repository.snapshot().outcome);
        }
    }
}
