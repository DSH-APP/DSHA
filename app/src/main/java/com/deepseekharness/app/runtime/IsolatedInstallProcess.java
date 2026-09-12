package com.deepseekharness.app.runtime;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessIdentity;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** 仅用于冷安装：宿主监督进程持有独立会话，退出/超时/中断均回收本次全部子进程。 */
final class IsolatedInstallProcess extends Process implements AutoCloseable {
    private final Process supervisor;
    private final ProcessIdentity identity;
    private final File status;
    private volatile boolean closing, closed;

    static boolean supported() {
        return android.os.Build.VERSION.SDK_INT >= 26 && new File("/system/bin/setsid").canExecute();
    }

    @android.annotation.SuppressLint("NewApi") // 入口先检查 API 26 与 setsid，再访问 ProcessBuilder.Redirect。
    static IsolatedInstallProcess start(ProcessBuilder target, File temporary) throws IOException {
        if (!supported()) throw new IOException(com.deepseekharness.app.util.UiText.text("此系统不支持独立安装进程组"));
        File status = new File(temporary, "cold-install-" + java.util.UUID.randomUUID() + ".status");
        StringBuilder shell = new StringBuilder("IFS= read -r DSHA_START || exit 125\n"
                + "[ \"$DSHA_START\" = DSHA_START ] || exit 125\n");
        for (String argument : target.command()) shell.append(ShellQuote.arg(argument)).append(' ');
        // 完成后仍保留组长，直到宿主核验身份并回收，避免 PID/进程组号复用的歧义。
        shell.append("\nresult=$?\nprintf '%s' \"$result\" > ").append(ShellQuote.arg(status.getAbsolutePath()))
                .append("\nkill -STOP $$\nexit 125\n");
        target.command(Arrays.asList("/system/bin/setsid", "/system/bin/sh", "-c", shell.toString()));
        target.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process supervisor = target.start();
        int pid = ProcessIdentity.androidPid(supervisor.getClass().getName(), supervisor.toString());
        long deadline = SystemClock.elapsedRealtime() + 2000;
        boolean accepted = false;
        IsolatedInstallProcess ready = null;
        try {
            while (!ProcessTermination.exited(supervisor) && SystemClock.elapsedRealtime() < deadline) {
                ProcessIdentity identity = readIdentity(pid);
                if (identity != null && identity.ownsSession()) {
                    ready = new IsolatedInstallProcess(supervisor, identity, status);
                    supervisor.getOutputStream().write("DSHA_START\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    supervisor.getOutputStream().flush();
                    accepted = true;
                    return ready;
                }
                Thread.sleep(5);
            }
            throw new IOException(com.deepseekharness.app.util.UiText.text("无法建立独立安装进程组"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException(com.deepseekharness.app.util.UiText.text("安装准备被中断"), error);
        } finally {
            // 握手前只等 stdin，没有创建 guest 子进程；握手写入失败也回收整个已核验的组。
            if (!accepted) {
                if (ready != null) ready.close(); else Compat.destroy(supervisor);
                status.delete();
            }
        }
    }

    private IsolatedInstallProcess(Process supervisor, ProcessIdentity identity, File status) {
        this.supervisor = supervisor; this.identity = identity; this.status = status;
    }

    private static ProcessIdentity readIdentity(int pid) {
        if (pid <= 1) return null;
        try { return ProcessIdentity.fromStat(Compat.readAll(new File("/proc/" + pid + "/stat")), pid, android.os.Process.myPid()); }
        catch (IOException | RuntimeException error) { return null; }
    }

    @Override public int exitValue() {
        if (closing && !closed && groupGone() && ProcessTermination.exited(supervisor)) {
            closed = true; status.delete();
        }
        if (closed) return supervisor.exitValue();
        if (!closing && status.isFile()) {
            try {
                String value = Compat.readAll(status);
                if (value.matches("[0-9]{1,3}")) {
                    int code = Integer.parseInt(value); if (code <= 255) return code;
                }
            } catch (IOException ignored) { }
        }
        throw new IllegalThreadStateException(com.deepseekharness.app.util.UiText.text("安装进程组尚未结束"));
    }
    @Override public boolean isAlive() {
        try { exitValue(); return false; } catch (IllegalThreadStateException running) { return true; }
    }
    @Override public int waitFor() throws InterruptedException {
        while (true) { try { return exitValue(); } catch (IllegalThreadStateException running) { Thread.sleep(10); } }
    }
    @Override public InputStream getInputStream() { return supervisor.getInputStream(); }
    @Override public InputStream getErrorStream() { return supervisor.getErrorStream(); }
    @Override public OutputStream getOutputStream() { return supervisor.getOutputStream(); }
    @Override public void destroy() { close(); }
    @Override public Process destroyForcibly() { close(); return this; }

    @Override public synchronized void close() {
        if (closed) return;
        closing = true;
        ProcessIdentity current = readIdentity(identity.pid);
        if (!identity.sameProcess(current) || !current.ownsSession())
            throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("安装进程组身份不可确认，已停止后续维护"));
        try { Os.kill(-identity.pid, OsConstants.SIGKILL); }
        catch (android.system.ErrnoException error) { throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("无法回收安装进程组"), error); }
        if (!ProcessTermination.awaitExit(supervisor, 3000))
            throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("安装监督进程未退出"));
        boolean interrupted = Thread.interrupted();
        long deadline = SystemClock.elapsedRealtime() + 3000;
        try {
            while (!groupGone() && SystemClock.elapsedRealtime() < deadline) {
                try { Thread.sleep(10); } catch (InterruptedException error) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (!groupGone()) throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("安装子进程尚未完全退出，已暂停后续维护"));
        closed = true;
        status.delete();
    }

    private boolean groupGone() {
        try { Os.kill(-identity.pid, 0); return false; }
        catch (android.system.ErrnoException error) { return error.errno == OsConstants.ESRCH; }
    }
}
