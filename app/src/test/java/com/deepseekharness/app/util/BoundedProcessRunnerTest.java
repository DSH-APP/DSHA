package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class BoundedProcessRunnerTest {
    private static class Pipe extends InputStream {
        volatile boolean closed;
        final ByteArrayInputStream bytes;
        Pipe(String text) { bytes = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
        @Override public int available() throws IOException { return bytes.available(); }
        @Override public int read() { throw new AssertionError("不得阻塞读取 EOF"); }
        @Override public int read(byte[] b, int off, int length) throws IOException {
            if (bytes.available() == 0) throw new AssertionError("空管道不应被 read");
            return bytes.read(b, off, length);
        }
        @Override public void close() { closed = true; }
    }

    private static final class Child extends Process {
        final Pipe output;
        final Pipe error = new Pipe("");
        volatile boolean alive = true, destroyed, stdinClosed;
        boolean exitOnStdinClose;
        final int code;
        final ByteArrayOutputStream stdin = new ByteArrayOutputStream() {
            @Override public void close() {
                stdinClosed = true;
                if (exitOnStdinClose) alive = false;
            }
        };
        Child(Pipe output, int code) { this.output = output; this.code = code; }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return error; }
        @Override public ByteArrayOutputStream getOutputStream() { return stdin; }
        @Override public int waitFor() { throw new AssertionError("不得无期限 waitFor"); }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return code;
        }
        @Override public void destroy() { destroyed = true; alive = false; }
        void assertClosed() { assertTrue(stdinClosed); assertTrue(output.closed); assertTrue(error.closed); }
    }

    @Test public void silentCommandTimesOutWithoutWaitingForEof() throws Exception {
        Child p = new Child(new Pipe(""), 0);
        long start = System.nanoTime();
        BoundedProcessRunner.Result r = BoundedProcessRunner.collect(p, 60, 256, Process::destroy);
        assertTrue(r.timedOut); assertTrue(p.destroyed); p.assertClosed();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
    }

    @Test public void infiniteOutputRemainsBoundedAndCannotStarveDeadline() throws Exception {
        Pipe endless = new Pipe("") {
            @Override public int available() { return 8192; }
            @Override public int read(byte[] b, int off, int length) {
                Arrays.fill(b, off, off + length, (byte) 'x'); return length;
            }
        };
        Child p = new Child(endless, 0);
        BoundedProcessRunner.Result r = BoundedProcessRunner.collect(p, 40, 1024, Process::destroy);
        assertTrue(r.timedOut); assertTrue(r.truncated); assertEquals(1024, r.output.length());
        assertTrue(p.destroyed); p.assertClosed();
    }

    @Test public void inheritedPipeDoesNotDelayAnExitedParent() throws Exception {
        Child p = new Child(new Pipe("尾部输出"), 17); p.alive = false;
        long start = System.nanoTime();
        BoundedProcessRunner.Result r = BoundedProcessRunner.collect(p, 5000, 256, Process::destroy);
        assertEquals("尾部输出", r.output); assertEquals(17, r.exitCode);
        assertFalse(r.timedOut); assertFalse(r.truncated); assertFalse(p.destroyed); p.assertClosed();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
    }

    @Test public void stdinEofLetsReadOnlyCommandExit() throws Exception {
        Child p = new Child(new Pipe("done"), 0); p.exitOnStdinClose = true;
        BoundedProcessRunner.Result r = BoundedProcessRunner.collect(p, 1000, 16, Process::destroy);
        assertEquals(0, r.exitCode); assertFalse(r.timedOut); p.assertClosed();
    }

    @Test public void readFailureStillTerminatesAndClosesEveryStream() throws Exception {
        Child p = new Child(new Pipe("") {
            @Override public int available() { return 1; }
            @Override public int read(byte[] b, int off, int length) throws IOException { throw new IOException("测试读失败"); }
        }, 0);
        try { BoundedProcessRunner.collect(p, 1000, 16, Process::destroy); fail("必须返回读取失败"); }
        catch (IOException expected) { assertEquals("测试读失败", expected.getMessage()); }
        assertTrue(p.destroyed); p.assertClosed();
    }

    @Test public void interruptionReleasesTheCallerAndProcess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        Child p = new Child(new Pipe("") {
            @Override public int available() { entered.countDown(); return 0; }
        }, 0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { BoundedProcessRunner.collect(p, 10_000, 16, Process::destroy); }
            catch (Throwable e) { failure.set(e); }
        });
        worker.setDaemon(true); worker.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS)); worker.interrupt(); worker.join(1500);
        assertFalse(worker.isAlive()); assertTrue(failure.get() instanceof InterruptedException);
        assertTrue(p.destroyed); p.assertClosed();
    }

    private Process startFixture(String mode) throws IOException {
        String executable = new File(System.getProperty("java.home"), "bin/java" +
                (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).getAbsolutePath();
        return new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                Fixture.class.getName(), mode).redirectErrorStream(true).start();
    }

    @Test public void realChildPreservesUtf8OutputAndExitCode() throws Exception {
        Process child = startFixture("eof");
        try {
            BoundedProcessRunner.Result r = BoundedProcessRunner.collect(child, 5000, 4096, Process::destroyForcibly);
            assertFalse(r.timedOut); assertEquals(7, r.exitCode); assertEquals("收到 EOF，测试完成", r.output);
        } finally { child.destroyForcibly(); }
    }

    @Test public void realSilentChildCannotOccupyAWorkerForever() throws Exception {
        Process child = startFixture("silent");
        try {
            BoundedProcessRunner.Result r = BoundedProcessRunner.collect(child, 500, 4096, Process::destroyForcibly);
            assertTrue(r.timedOut); assertTrue(child.waitFor(2, TimeUnit.SECONDS));
        } finally { child.destroyForcibly(); }
    }

    @Test public void realFloodingChildIsCappedAndKilled() throws Exception {
        Process child = startFixture("flood");
        try {
            BoundedProcessRunner.Result r = BoundedProcessRunner.collect(child, 2000, 1024, Process::destroyForcibly);
            assertTrue(r.timedOut); assertTrue(r.truncated); assertEquals(1024, r.output.length());
            assertTrue(child.waitFor(2, TimeUnit.SECONDS));
        } finally { child.destroyForcibly(); }
    }

    /** 独立 JVM 测试进程：不访问设备、不联网，也不读取用户环境或凭据。 */
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("silent")) Thread.sleep(30_000);
            else if (args[0].equals("flood")) {
                byte[] data = new byte[8192]; Arrays.fill(data, (byte) 'x');
                while (true) { System.out.write(data); System.out.flush(); }
            } else {
                if (System.in.read() != -1) throw new AssertionError("stdin 未关闭");
                System.out.write("收到 EOF，测试完成".getBytes(StandardCharsets.UTF_8));
                System.out.flush(); System.exit(7);
            }
        }
    }
}
