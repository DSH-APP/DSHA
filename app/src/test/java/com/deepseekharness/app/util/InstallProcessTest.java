package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class InstallProcessTest {
    private Process child(String mode) throws IOException {
        return new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), Child.class.getName(), mode).redirectErrorStream(true).start();
    }
    @Test public void receivesEachLineBeforeProcessExitAndKeepsNonzeroExit() throws Exception {
        Process process = child("stream"); CountDownLatch first = new CountDownLatch(1);
        List<String> lines = new ArrayList<>();
        FutureTask<Integer> read = new FutureTask<>(() -> InstallProcess.read(process, 5000, true, () -> false,
                line -> { lines.add(line); first.countDown(); }, Process::destroyForcibly));
        new Thread(read).start(); assertTrue(first.await(3, TimeUnit.SECONDS));
        assertFalse("首行必须在进程结束前可见", read.isDone());
        assertEquals(7, (int) read.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("第一行", "第二行", "最后一行"), lines);
    }
    @Test public void cancelReadOnlyProcessReclaimsIt() throws Exception {
        Process process = child("wait"); AtomicBoolean cancelled = new AtomicBoolean();
        assertThrows(InstallTask.Cancelled.class, () -> InstallProcess.read(process, 5000, true,
                cancelled::get, line -> cancelled.set(true), Process::destroyForcibly));
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
    }
    @Test public void cancellationDoesNotInterruptWritingCommand() throws Exception {
        Process process = child("stream");
        assertEquals(7, InstallProcess.read(process, 5000, false, () -> true, line -> { }, Process::destroyForcibly));
    }
    @Test public void timeoutAndCallbackFailureReclaimProcess() throws Exception {
        Process timeout = child("wait");
        IOException error = assertThrows(IOException.class, () -> InstallProcess.read(timeout, 150, true,
                () -> false, line -> { }, Process::destroyForcibly));
        assertTrue(error.getMessage().contains("超时")); assertTrue(timeout.waitFor(2, TimeUnit.SECONDS));
        Process callback = child("wait");
        assertThrows(Exception.class, () -> InstallProcess.read(callback, 5000, true, () -> false,
                line -> { throw new IllegalStateException("回调异常"); }, Process::destroyForcibly));
        assertTrue(callback.waitFor(2, TimeUnit.SECONDS));
    }
    @Test public void oversizedCredentialLineIsNeverPartiallyEmitted() throws Exception {
        Process process = child("large"); InstallTask task = new InstallTask(); task.start(false, 0);
        assertEquals(0, InstallProcess.read(process, 5000, true, () -> false, task::append, Process::destroyForcibly));
        String log = task.snapshot().log;
        assertTrue(log.contains("输出行过长")); assertTrue(log.contains("正常尾行")); assertFalse(log.contains("SECRET"));
    }
    @Test public void cancelDoesNotReturnBeforeAsynchronousProcessExit() throws Exception {
        FakeProcess process = new FakeProcess("");
        Thread reaper = new Thread(() -> { try { Thread.sleep(100); process.alive = false; } catch (InterruptedException e) { throw new AssertionError(e); } });
        assertThrows(InstallTask.Cancelled.class, () -> InstallProcess.read(process, 5000, true, () -> true,
                line -> { }, p -> reaper.start()));
        assertFalse("取消返回时进程必须已退出", process.alive); reaper.join();
    }
    @Test public void ineffectiveDestroyCannotBeReportedAsSuccessfulCancellation() {
        FakeProcess process = new FakeProcess("");
        InstallProcess.CleanupFailure failure = assertThrows(InstallProcess.CleanupFailure.class,
                () -> InstallProcess.read(process, 5000, true, () -> true, line -> { }, Process::destroy));
        assertTrue(process.alive); assertFalse(failure.awaitExit(0));
        assertTrue(failure.getSuppressed()[0] instanceof InstallTask.Cancelled);
        process.alive = false; assertTrue(failure.awaitExit(0));
    }
    @Test public void utf8SplitAcrossSmallReadsKeepsLinesAndFinalTail() throws Exception {
        FakeProcess process = new FakeProcess("第一行\r\n第二行\n末尾😀"); process.alive = false;
        List<String> lines = new ArrayList<>();
        assertEquals(0, InstallProcess.read(process, 5000, true, () -> false, lines::add, Process::destroy));
        assertEquals(List.of("第一行", "第二行", "末尾😀"), lines);
    }
    private static final class FakeProcess extends Process {
        volatile boolean alive = true;
        final java.io.InputStream input;
        FakeProcess(String text) {
            input = new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                @Override public int available() { return Math.min(2, super.available()); }
                @Override public synchronized int read(byte[] b, int off, int len) {
                    if (available() == 0) throw new AssertionError("不应阻塞等 EOF");
                    return super.read(b, off, len);
                }
            };
        }
        @Override public java.io.InputStream getInputStream() { return input; }
        @Override public java.io.InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        @Override public int waitFor() { throw new AssertionError("不应无限等待"); }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { }
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            if ("large".equals(args[0])) {
                System.out.print("password='" + "SECRET".repeat(2000) + "'\r\n正常尾行"); return;
            }
            System.out.println("第一行"); System.out.flush();
            if ("wait".equals(args[0])) { Thread.sleep(30_000); return; }
            Thread.sleep(600); System.out.print("第二行\r最后一行"); System.out.flush(); System.exit(7);
        }
    }
}
