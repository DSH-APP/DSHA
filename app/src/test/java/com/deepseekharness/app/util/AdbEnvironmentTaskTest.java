package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AdbEnvironmentTaskTest {
    @Test public void maintenanceExcludesAdbBeforeAnyPreparation() {
        AtomicBoolean prepared = new AtomicBoolean();
        try (EnvironmentTaskGate.Lease maintenance = EnvironmentTaskGate.tryAcquire("维护")) {
            AdbEnvironmentTask.Busy error = assertThrows(AdbEnvironmentTask.Busy.class,
                    () -> AdbEnvironmentTask.run("ADB", () -> false, () -> { prepared.set(true); return null; }));
            assertEquals(AdbEnvironmentTask.BLOCKED, error.getMessage());
            assertFalse(prepared.get()); assertTrue(EnvironmentTaskGate.isBusy());
        }
        assertFalse(EnvironmentTaskGate.isBusy());
    }

    @Test public void pendingJournalIsCheckedUnderLeaseAndNeverTouchesRootfs() {
        AtomicBoolean called = new AtomicBoolean();
        assertThrows(AdbEnvironmentTask.Busy.class, () -> AdbEnvironmentTask.run("ADB", () -> {
            assertTrue(EnvironmentTaskGate.ownsCurrentThread()); return true;
        }, () -> { called.set(true); return null; }));
        assertFalse(called.get()); assertFalse(EnvironmentTaskGate.isBusy());
    }

    @Test public void nestedSetupUsesOwnersLeaseWithoutReleasingIt() throws Exception {
        try (EnvironmentTaskGate.Lease owner = EnvironmentTaskGate.tryAcquire("完整配对")) {
            String result = owner.run(() -> AdbEnvironmentTask.run("准备", () -> false,
                    () -> AdbEnvironmentTask.run("同步设置", () -> false, () -> {
                        assertTrue(EnvironmentTaskGate.ownsCurrentThread());
                        assertEquals("完整配对", EnvironmentTaskGate.activeKind()); return "done";
                    })));
            assertEquals("done", result);
            assertTrue(EnvironmentTaskGate.isBusy()); assertNull(EnvironmentTaskGate.tryAcquire("维护"));
        }
        assertFalse(EnvironmentTaskGate.isBusy());
    }

    @Test public void ownerStillCannotPrepareAcrossPendingJournal() throws Exception {
        try (EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("配置")) {
            lease.run(() -> {
                assertThrows(AdbEnvironmentTask.Busy.class, () -> AdbEnvironmentTask.run("ADB", () -> true, () -> null));
                assertTrue(EnvironmentTaskGate.ownsCurrentThread()); return null;
            });
        }
    }

    @Test public void successExceptionAndInterruptAllReleaseOnlyTheirOwnLease() throws Exception {
        assertEquals("ready", AdbEnvironmentTask.run("ADB", () -> false, () -> "ready"));
        assertFalse(EnvironmentTaskGate.isBusy());
        assertThrows(java.io.IOException.class, () -> AdbEnvironmentTask.run("ADB", () -> false,
                () -> { throw new java.io.IOException("fixture"); }));
        assertFalse(EnvironmentTaskGate.isBusy()); assertFalse(EnvironmentTaskGate.ownsCurrentThread());
        assertThrows(InterruptedException.class, () -> AdbEnvironmentTask.run("ADB", () -> false,
                () -> { throw new InterruptedException(); }));
        assertFalse(EnvironmentTaskGate.isBusy());
        assertThrows(IllegalStateException.class, () -> AdbEnvironmentTask.run("ADB", () -> { throw new IllegalStateException(); }, () -> null));
        assertFalse(EnvironmentTaskGate.isBusy());
    }

    @Test public void fullPairingKeepsLeaseThroughSetupAndVerification() throws Exception {
        CountDownLatch verifying = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                AdbEnvironmentTask.run("配对及验证", () -> false, () -> {
                    AdbEnvironmentTask.run("ensureReady", () -> false, () -> "SETUP_DONE");
                    verifying.countDown(); finish.await();
                    assertTrue(EnvironmentTaskGate.ownsCurrentThread()); return "CONNECT_OK";
                });
            } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        try {
            assertTrue(verifying.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(EnvironmentTaskGate.tryAcquire("移动 linux"));
            assertThrows(AdbEnvironmentTask.Busy.class,
                    () -> AdbEnvironmentTask.run("后台探活", () -> false, () -> "probe"));
        } finally { finish.countDown(); worker.join(2000); }
        assertFalse(worker.isAlive()); assertNull(failure.get()); assertFalse(EnvironmentTaskGate.isBusy());
    }

    @Test public void cancellationKeepsLeaseUntilProcessActuallyExits() throws Exception {
        AtomicBoolean alive = new AtomicBoolean(true);
        CountDownLatch terminating = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Process fake = new Process() {
            public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
            public java.io.InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
            public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
            public int waitFor() { throw new AssertionError("不应无限等待进程"); }
            public int exitValue() { if (alive.get()) throw new IllegalThreadStateException(); return 0; }
            public void destroy() { terminating.countDown(); }
        };
        Thread worker = new Thread(() -> {
            try {
                AdbEnvironmentTask.run("ADB 回收", () -> false,
                        () -> InstallProcess.read(fake, 5000, true, () -> true, line -> { }, Process::destroy));
            } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        try {
            assertTrue(terminating.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(EnvironmentTaskGate.isBusy());
            assertNull(EnvironmentTaskGate.tryAcquire("移动 linux"));
        } finally { alive.set(false); worker.join(3000); }
        assertFalse(worker.isAlive());
        assertTrue(failure.get() instanceof InstallTask.Cancelled);
        assertFalse(EnvironmentTaskGate.isBusy());
    }
}
