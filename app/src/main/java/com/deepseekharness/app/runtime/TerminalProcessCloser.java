package com.deepseekharness.app.runtime;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessIdentity;
import java.io.File;
import java.io.IOException;

/** 维护中回收真实 PTY 的 proroot 会话；组长存活时才核验、结束同 UID 会话成员。 */
public final class TerminalProcessCloser {
    private TerminalProcessCloser() { }

    public static void close(ProcessIdentity expected, long timeoutMs) throws IOException, InterruptedException {
        ProcessIdentity leader = read(expected.pid, expected.pid);
        if (!expected.sameProcess(leader) || !leader.ownsSession())
            throw new IOException("无法核验终端独立会话，原环境保持保护");
        boolean ended = false;
        try {
            // 保留组长，避免清理期间会话号复用；先结束 guest，最后结束启动器。
            signal(leader, OsConstants.SIGSTOP);
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (true) {
                if (!expected.sameProcess(read(expected.pid, expected.pid)))
                    throw new IOException("终端组长身份已变化，停止回收");
                File[] processes = new File("/proc").listFiles();
                if (processes == null) throw new IOException("无法核验终端后台进程");
                boolean active = false;
                for (File file : processes) {
                    if (!file.getName().matches("[1-9][0-9]*")) continue;
                    int pid;
                    try { pid = Integer.parseInt(file.getName()); } catch (NumberFormatException ignored) { continue; }
                    if (pid == expected.pid) continue;
                    try {
                        if (Os.stat(file.getPath()).st_uid != android.os.Process.myUid()) continue;
                    } catch (android.system.ErrnoException error) {
                        if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) continue;
                        throw new IOException("无法核验终端进程归属", error);
                    }
                    ProcessIdentity member = read(pid, expected.pid);
                    if (member == null || member.exited()) continue;
                    active = true;
                    signal(member, OsConstants.SIGKILL);
                }
                if (!active) break;
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("终端后台命令尚未退出，原环境保持保护");
                Thread.sleep(20);
            }
            signal(leader, OsConstants.SIGKILL);
            ended = true;
        } finally {
            if (!ended) signal(leader, OsConstants.SIGCONT);
        }
    }

    private static ProcessIdentity read(int pid, int session) throws IOException {
        try { return ProcessIdentity.inSession(Compat.readAll(new File("/proc/" + pid + "/stat")), pid, session); }
        catch (java.io.FileNotFoundException gone) {
            if (!new File("/proc/" + pid).exists()) return null;
            throw gone;
        }
    }

    private static void signal(ProcessIdentity expected, int signal) throws IOException {
        ProcessIdentity current = read(expected.pid, expected.session);
        if (current == null || current.exited()) return;
        if (!expected.sameProcess(current)) throw new IOException("终端进程身份已变化，未发送信号");
        try { Os.kill(expected.pid, signal); }
        catch (android.system.ErrnoException error) {
            if (error.errno != OsConstants.ESRCH) throw new IOException("终端进程回收失败", error);
        }
    }
}
