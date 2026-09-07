package com.deepseekharness.app.util;

import org.junit.After;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** 无 Android/Gradle/设备依赖，控制握手、EOF、写入失败和过期输出的竞态。 */
public final class TerminalSessionTest {
    private final FakeBackend backend = new FakeBackend();
    private final StringBuffer output = new StringBuffer();
    private final TerminalSession terminal = new TerminalSession(backend, output::append, ignored -> { });

    @After public void cleanup() throws Exception {
        backend.failStop = false;
        terminal.shutdown();
        for (FakeProcess process : backend.processes) process.finish();
    }

    @Test public void queuedFirstCommandWaitsForHandshakeAndKeepsOrder() throws Exception {
        assertTrue(terminal.submit("echo first"));
        assertTrue(terminal.submit("echo second"));
        FakeProcess shell = backend.opened(0);
        await(() -> shell.boot != null);
        assertTrue(shell.commands.isEmpty());
        shell.ready();
        await(() -> shell.commands.size() == 1);
        assertTrue(shell.commands.get(0).contains("echo first"));
        shell.complete(0);
        await(() -> shell.commands.size() == 2);
        assertTrue(shell.commands.get(1).contains("echo second"));
        shell.complete(1);
        await(() -> terminal.state() == TerminalSession.State.READY);
        assertEquals(1, backend.processes.size());
    }

    @Test public void exitThenImmediateCommandRunsOnceOnNewShell() throws Exception {
        terminal.submit("exit");
        FakeProcess old = backend.opened(0); old.ready();
        await(() -> old.commands.size() == 1);
        terminal.submit("echo after-exit");
        old.finish();
        FakeProcess next = backend.opened(1); next.ready();
        await(() -> next.commands.size() == 1);
        assertTrue(next.commands.get(0).contains("echo after-exit"));
        assertEquals(1, old.commands.size());
        next.complete(0);
        await(() -> terminal.state() == TerminalSession.State.READY);
        assertEquals(1, next.commands.size());
    }

    @Test public void cancelKillsOwnedGroupAndRetainsUnsentCommand() throws Exception {
        terminal.submit("sleep 60");
        FakeProcess old = backend.opened(0); old.ready();
        await(() -> old.commands.size() == 1);
        terminal.submit("echo after-cancel");
        terminal.cancelAndRestart();
        FakeProcess next = backend.opened(1); next.ready();
        await(() -> next.commands.size() == 1);
        assertEquals(Long.valueOf(old.group), backend.killed.get(0));
        assertFalse(old.alive);
        assertTrue(next.commands.get(0).contains("echo after-cancel"));
        assertEquals(1, old.commands.size());
        assertFalse(old.writes.toString().contains("\003"));
        assertTrue(output.toString().contains("cd/export"));
    }

    @Test public void cancelDuringStartupWaitsForIdentityBeforeSendingAnything() throws Exception {
        terminal.submit("echo pending");
        FakeProcess old = backend.opened(0);
        terminal.cancelAndRestart();
        await(() -> terminal.state() == TerminalSession.State.STOPPING);
        assertTrue(old.commands.isEmpty());
        assertTrue(backend.killed.isEmpty());
        old.ready();
        FakeProcess next = backend.opened(1); next.ready();
        await(() -> next.commands.size() == 1);
        assertTrue(old.commands.isEmpty());
        assertEquals(Long.valueOf(old.group), backend.killed.get(0));
    }

    @Test public void partialWriteIsReportedAndNeverReplayed() throws Exception {
        terminal.ensureStarted();
        FakeProcess old = backend.opened(0); old.ready();
        await(() -> terminal.state() == TerminalSession.State.READY);
        old.failCommandWrite = true;
        terminal.submit("touch side-effect");
        terminal.submit("echo next");
        FakeProcess next = backend.opened(1); next.ready();
        await(() -> next.commands.size() == 1);
        assertTrue(output.toString().contains("发送结果不确定，未自动重发：touch side-effect"));
        assertTrue(next.commands.get(0).contains("echo next"));
        assertFalse(next.commands.get(0).contains("touch side-effect"));
    }

    @Test public void failedKillDoesNotClaimReadyOrCreateConcurrentShell() throws Exception {
        terminal.submit("sleep 60");
        FakeProcess old = backend.opened(0); old.ready();
        await(() -> old.commands.size() == 1);
        backend.failStop = true;
        terminal.cancelAndRestart();
        await(() -> terminal.state() == TerminalSession.State.FAILED);
        terminal.submit("echo queued");
        old.complete(0); // 旧完成回执不能撤销中止失败状态或发送新命令。
        await(() -> output.toString().contains("$ echo queued"));
        assertEquals(1, backend.processes.size());
        assertEquals(1, old.commands.size());
        assertEquals(TerminalSession.State.FAILED, terminal.state());
        backend.failStop = false;
        terminal.cancelAndRestart();
        FakeProcess next = backend.opened(1); next.ready();
        await(() -> next.commands.size() == 1);
    }

    @Test public void oldReaderCannotCloseOrUnlockNewSession() throws Exception {
        backend.keepOldReader = true;
        terminal.submit("sleep 60");
        FakeProcess old = backend.opened(0); old.ready();
        await(() -> old.commands.size() == 1);
        terminal.cancelAndRestart();
        FakeProcess next = backend.opened(1); next.ready();
        terminal.submit("sleep 10");
        await(() -> next.commands.size() == 1);
        old.complete(0);
        old.emit("STALE_OUTPUT");
        old.closeOutput();
        terminal.submit("echo after");
        await(() -> output.toString().contains("$ echo after"));
        assertEquals(1, next.commands.size());
        assertEquals(TerminalSession.State.BUSY, terminal.state());
        assertFalse(output.toString().contains("STALE_OUTPUT"));
        next.complete(0);
        await(() -> next.commands.size() == 2);
    }

    @Test public void startupFailureKeepsCommandsWithoutRestartLoop() throws Exception {
        terminal.submit("echo retained");
        FakeProcess old = backend.opened(0);
        old.finish();
        await(() -> output.toString().contains("旧会话已结束"));
        assertEquals(1, backend.processes.size());
        terminal.ensureStarted();
        FakeProcess next = backend.opened(1); next.ready();
        await(() -> next.commands.size() == 1);
        assertTrue(next.commands.get(0).contains("echo retained"));
    }

    @Test public void protocolCanBeSplitAcrossChunksWithoutLeakingMarkers() throws Exception {
        terminal.ensureStarted();
        FakeProcess shell = backend.opened(0);
        await(() -> shell.prefix != null);
        String ready = shell.prefix + "READY:" + shell.group + "\037";
        shell.emit("hello");
        for (char ch : ready.toCharArray()) shell.emit(String.valueOf(ch));
        shell.emit("world");
        await(() -> output.toString().contains("world"));
        assertEquals(TerminalSession.State.READY, terminal.state());
        assertTrue(output.toString().contains("hello"));
        assertFalse(output.toString().contains(shell.prefix));
    }

    @Test public void invalidCommandIsNotAcceptedOrCleared() {
        assertFalse(terminal.submit(" "));
        assertFalse(terminal.submit("echo\0bad"));
        assertFalse(terminal.submit(new String(new char[16385]).replace('\0', 'x')));
        assertTrue(backend.processes.isEmpty());
    }

    @Test public void silentPipeNeverEntersABlockingRead() throws Exception {
        backend.rejectEmptyReads = true;
        terminal.ensureStarted();
        FakeProcess shell = backend.opened(0);
        await(() -> shell.availableCalls.get() >= 4);
        assertFalse("静默管道不应调用 read", shell.emptyReadAttempted);
        shell.ready();
        await(() -> terminal.state() == TerminalSession.State.READY);
        assertFalse(shell.emptyReadAttempted);
        assertTrue(terminal.shutdownAndWait(2000));
    }

    @Test public void utf8SurvivesByteAtATimePipeReads() throws Exception {
        terminal.ensureStarted();
        FakeProcess shell = backend.opened(0); shell.ready();
        await(() -> terminal.state() == TerminalSession.State.READY);
        // FakeProcess 每次 read 仅返回一个字节，覆盖汉字和代理对跨批次。
        shell.emit("UTF8：中文😀终端\n");
        await(() -> output.toString().contains("UTF8：中文😀终端\n"));
        assertFalse(output.toString().contains("\uFFFD"));
    }

    @Test public void environmentRejectionKeepsUnsentCommandForNextShell() throws Exception {
        terminal.ensureStarted();
        FakeProcess old = backend.opened(0); old.ready();
        await(() -> terminal.state() == TerminalSession.State.READY);
        backend.environmentBlocked = true;
        terminal.submit("echo after-maintenance");
        await(() -> terminal.state() == TerminalSession.State.STOPPED);
        assertTrue(old.commands.isEmpty());
        assertTrue(output.toString().contains("命令尚未发送，已保留"));
        backend.environmentBlocked = false;
        terminal.ensureStarted();
        FakeProcess next = backend.opened(1); next.ready();
        await(() -> next.commands.size() == 1);
        assertTrue(next.commands.get(0).contains("echo after-maintenance"));
        assertEquals(1, next.commands.size());
    }

    @Test public void maintenanceWaitsForStartingShellToBeReaped() throws Exception {
        terminal.ensureStarted();
        FakeProcess shell = backend.opened(0);
        java.util.concurrent.FutureTask<Boolean> stopped = new java.util.concurrent.FutureTask<>(() -> terminal.shutdownAndWait(3000));
        Thread waiter = new Thread(stopped); waiter.start();
        await(() -> terminal.state() == TerminalSession.State.STOPPING);
        assertFalse(stopped.isDone());
        shell.ready();
        assertTrue(stopped.get(3, TimeUnit.SECONDS));
        assertFalse(shell.alive);
        assertEquals(1, backend.processes.size());
    }

    @Test public void failedKillCannotAuthorizeEnvironmentMove() throws Exception {
        backend.registry = new RuntimeTaskRegistry();
        terminal.submit("sleep 60");
        FakeProcess shell = backend.opened(0); shell.ready();
        await(() -> shell.commands.size() == 1);
        assertTrue(backend.registeredBeforeOpen);
        backend.failStop = true;
        assertFalse(terminal.shutdownAndWait(1000));
        assertTrue(shell.alive);
        assertEquals(1, backend.registry.count());
        assertNull(backend.registry.tryEnterMaintenance());
        backend.failStop = false;
        assertTrue(terminal.shutdownAndWait(2000));
        assertEquals(0, backend.registry.count());
    }

    @Test public void maintenanceFenceRejectsBeforeBackendPreparation() throws Exception {
        backend.registry = new RuntimeTaskRegistry();
        try (RuntimeTaskRegistry.Maintenance fence = backend.registry.tryEnterMaintenance()) {
            assertNotNull(fence);
            terminal.ensureStarted();
            await(() -> terminal.state() == TerminalSession.State.FAILED);
            assertTrue("fence 内不能调用 open/prepare", backend.processes.isEmpty());
            assertEquals(0, backend.registry.count());
        }
        terminal.ensureStarted();
        FakeProcess shell = backend.opened(0); shell.ready();
        await(() -> terminal.state() == TerminalSession.State.READY);
        assertTrue(backend.registeredBeforeOpen);
        assertNull(backend.registry.tryEnterMaintenance());
        assertTrue(terminal.shutdownAndWait(2000));
    }

    @Test public void definiteOpenFailureReleasesDetachedLifetime() throws Exception {
        backend.registry = new RuntimeTaskRegistry();
        backend.openFailure = new IOException("在创建进程前失败");
        terminal.ensureStarted();
        await(() -> terminal.state() == TerminalSession.State.FAILED);
        assertEquals(0, backend.registry.count());
        try (RuntimeTaskRegistry.Maintenance fence = backend.registry.tryEnterMaintenance()) { assertNotNull(fence); }
    }

    @Test public void unknownForkOutcomeKeepsMaintenanceBlocked() throws Exception {
        backend.registry = new RuntimeTaskRegistry();
        backend.openFailure = new TerminalSession.UncertainStart(new IllegalStateException("fork 后未返回句柄"));
        terminal.ensureStarted();
        await(() -> terminal.state() == TerminalSession.State.FAILED);
        assertEquals(1, backend.registry.count());
        assertFalse(terminal.shutdownAndWait(1000));
        assertNull(backend.registry.tryEnterMaintenance());
        assertTrue(output.toString().contains("保留环境占用"));
    }

    @Test public void realPosixSleepCancellationAndShellState() throws Exception {
        // Windows 主机跳过；Linux CI 可运行真实进程组、sleep 和 bash 语义回归，无需 Android。
        org.junit.Assume.assumeTrue(new File("/usr/bin/setsid").canExecute() && new File("/bin/bash").canExecute());
        File marker = File.createTempFile("dsha-terminal-cancel-", ".txt");
        assertTrue(marker.delete());
        StringBuffer realOutput = new StringBuffer();
        TerminalSession real = new TerminalSession(new TerminalSession.Backend() {
            @Override public Process open() throws IOException {
                return new ProcessBuilder("/bin/bash").redirectErrorStream(true).start();
            }
            @Override public void terminate(Process process, long group) throws Exception {
                if (group > 1) {
                    Process kill = new ProcessBuilder("/bin/bash", "-c", "kill -KILL -- -" + group + " 2>/dev/null || true").start();
                    assertTrue(kill.waitFor(3, TimeUnit.SECONDS));
                }
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                assertTrue(process.waitFor(2, TimeUnit.SECONDS));
            }
        }, realOutput::append, ignored -> { });
        try {
            real.submit("printf '\\nCHILD_READY\\n'; sleep 2; printf BAD > " + ShellQuote.arg(marker.getAbsolutePath()));
            await(() -> realOutput.toString().contains("\nCHILD_READY\n"));
            real.submit("printf '\\nAFTER_CANCEL\\n'");
            real.cancelAndRestart();
            await(() -> realOutput.toString().contains("\nAFTER_CANCEL\n"));
            Thread.sleep(2300);
            assertFalse("sleep 子进程没有被真正中止", marker.exists());
            real.submit("export DSHA_TERM_FIXTURE='中文 值'; cd /tmp");
            real.submit("printf '\\nSTATE=%s:%s\\n' \"$DSHA_TERM_FIXTURE\" \"$PWD\"");
            real.submit("false");
            real.submit("printf '\\nSTATUS=%s\\n' \"$?\"");
            real.submit("exit");
            real.submit("printf '\\nAFTER_EXIT\\n'");
            await(() -> realOutput.toString().contains("\nAFTER_EXIT\n"));
            assertTrue(realOutput.toString().contains("\nSTATE=中文 值:/tmp\n"));
            assertTrue(realOutput.toString().contains("\nSTATUS=1\n"));
            assertEquals(1, realOutput.toString().split("\nAFTER_EXIT\n", -1).length - 1);
        } finally {
            real.shutdown();
            await(() -> real.state() == TerminalSession.State.STOPPED);
            java.nio.file.Files.deleteIfExists(marker.toPath());
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue("等待异步会话事件超时", condition.getAsBoolean());
    }

    private static final class FakeBackend implements TerminalSession.Backend {
        final List<FakeProcess> processes = new CopyOnWriteArrayList<>();
        final List<Long> killed = new CopyOnWriteArrayList<>();
        volatile boolean failStop, keepOldReader, environmentBlocked, rejectEmptyReads;
        RuntimeTaskRegistry registry;
        volatile boolean registeredBeforeOpen;
        IOException openFailure;
        @Override public AutoCloseable beginLifetime() { return registry == null ? null : registry.begin(true); }
        @Override public Process open() throws IOException {
            registeredBeforeOpen = registry != null && registry.count() == 1 && registry.hasOtherTasks();
            if (openFailure != null) throw openFailure;
            FakeProcess process = new FakeProcess(3100 + processes.size());
            process.rejectEmptyReads = rejectEmptyReads;
            processes.add(process);
            return process;
        }
        @Override public void terminate(Process process, long group) throws IOException {
            if (failStop) throw new IOException("kill failed");
            killed.add(group);
            FakeProcess fake = (FakeProcess) process;
            fake.alive = false;
            if (!keepOldReader) fake.closeOutput();
        }
        @Override public void write(Process process, String text) throws IOException {
            if (environmentBlocked && text.startsWith("eval "))
                throw new TerminalSession.EnvironmentUnavailable("正在维护环境");
            TerminalSession.Backend.super.write(process, text);
        }
        FakeProcess opened(int index) throws Exception {
            await(() -> processes.size() > index);
            return processes.get(index);
        }
    }

    private static final class FakeProcess extends Process {
        final long group;
        final LinkedBlockingQueue<Integer> output = new LinkedBlockingQueue<>();
        final List<String> commands = new CopyOnWriteArrayList<>();
        final StringBuffer writes = new StringBuffer();
        volatile boolean alive = true, failCommandWrite;
        volatile boolean rejectEmptyReads, emptyReadAttempted;
        final java.util.concurrent.atomic.AtomicInteger availableCalls = new java.util.concurrent.atomic.AtomicInteger();
        volatile String boot, prefix;
        FakeProcess(long group) { this.group = group; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int b) { writes.append((char) b); }
                @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                    String text = new String(bytes, offset, count, StandardCharsets.UTF_8);
                    writes.append(text);
                    if (text.startsWith("exec ")) {
                        Matcher token = Pattern.compile("DSHA_[a-f0-9]+:").matcher(text);
                        assertTrue(token.find());
                        prefix = "\036" + token.group();
                        boot = text;
                    } else {
                        commands.add(text);
                        if (failCommandWrite) throw new IOException("partial pipe write");
                    }
                }
            };
        }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int available() { availableCalls.incrementAndGet(); return output.size(); }
                @Override public int read() throws IOException {
                    if (rejectEmptyReads && output.isEmpty()) {
                        emptyReadAttempted = true;
                        throw new IOException("不应进入空管道 read");
                    }
                    try { return output.take(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int value = read();
                    if (value == -1) return -1;
                    bytes[offset] = (byte) value;
                    return 1;
                }
            };
        }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { while (alive) Thread.sleep(5); return 0; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { finish(); }
        void emit(String text) { for (byte b : text.getBytes(StandardCharsets.UTF_8)) output.add(b & 255); }
        void ready() throws Exception { await(() -> prefix != null); emit(prefix + "READY:" + group + "\037"); }
        void complete(int command) {
            Matcher done = Pattern.compile("DONE:(\\d+):").matcher(commands.get(command));
            assertTrue(done.find());
            emit(prefix + "DONE:" + done.group(1) + ":0\037");
        }
        void closeOutput() { output.add(-1); }
        void finish() { alive = false; closeOutput(); }
    }
}
