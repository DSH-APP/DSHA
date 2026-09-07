package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ProcessTerminationTest {
    private static class Child extends Process {
        volatile boolean alive = true;
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return getInputStream(); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int waitFor() { throw new AssertionError("不能无限 waitFor"); }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { /* 模拟 Android SIGTERM 被 proot 忽略。 */ }
    }
    @Test public void prootCleanupProtocolRunsBeforeAnyForceKill() {
        Child child = new Child(); AtomicInteger quit = new AtomicInteger(), kill = new AtomicInteger();
        ProcessTermination.stop(child, () -> { quit.incrementAndGet(); child.alive = false; }, kill::incrementAndGet, 50, 50);
        assertEquals(1, quit.get()); assertEquals(0, kill.get()); assertFalse(child.alive);
    }
    @Test public void ignoredGracefulSignalEscalatesOnlyForPassedProcess() {
        Child child = new Child(), unrelated = new Child();
        ProcessTermination.stop(child, child::destroy, () -> child.alive = false, 10, 50);
        assertFalse(child.alive); assertTrue(unrelated.alive);
    }
    @Test public void asynchronousExitMustBeObservedBeforeReturning() throws Exception {
        Child child = new Child();
        Thread reaper = new Thread(() -> { try { Thread.sleep(100); child.alive = false; } catch (InterruptedException e) { throw new AssertionError(e); } });
        ProcessTermination.stop(child, null, reaper::start, 0, 1000);
        assertFalse(child.alive); reaper.join();
    }
    @Test public void ignoredForceRequestIsExplicitFailure() {
        Child child = new Child();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProcessTermination.stop(child, child::destroy, child::destroy, 10, 20));
        assertTrue(child.alive); assertTrue(error.getMessage().contains("仍未退出"));
    }
    @Test public void interruptedCleanupStillWaitsAndRestoresInterruptFlag() throws Exception {
        Child child = new Child();
        Thread reaper = new Thread(() -> { try { Thread.sleep(50); child.alive = false; } catch (InterruptedException e) { throw new AssertionError(e); } });
        try {
            Thread.currentThread().interrupt();
            ProcessTermination.stop(child, null, reaper::start, 0, 1000);
            assertFalse(child.alive); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); reaper.join(); }
    }
    @Test public void exitedProcessNeverReceivesAnotherSignal() {
        Child child = new Child(); child.alive = false;
        ProcessTermination.stop(child, () -> fail("退出后不能发 QUIT"), () -> fail("退出后不能发 KILL"), 1, 1);
    }
}
