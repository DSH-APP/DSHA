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

    /** proot 自己负责回收 tracee；结束后调用者还须核验本次独立会话已空。 */
    public static void closeProot(ProcessIdentity expected)throws IOException{
        ProcessIdentity current=read(expected.pid,expected.pid);
        if(current==null||current.exited())return;
        if(!expected.sameProcess(current)||!current.ownsSession())throw new IOException(com.deepseekharness.app.util.UiText.text("无法核验终端独立会话，原环境保持保护"));
        signal(expected,OsConstants.SIGQUIT);
    }

    public static void close(ProcessIdentity expected, long timeoutMs) throws IOException, InterruptedException {
        ProcessIdentity leader = read(expected.pid, expected.pid);
        if (leader == null || leader.exited()) {
            requireSessionEmpty(expected.pid);
            return;
        }
        if (!expected.sameProcess(leader) || !leader.ownsSession())
            throw new IOException(com.deepseekharness.app.util.UiText.text("无法核验终端独立会话，原环境保持保护"));
        boolean ended = false;
        try {
            // 保留组长，避免清理期间会话号复用；先结束 guest，最后结束启动器。
            signal(leader, OsConstants.SIGSTOP);
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (true) {
                if (!expected.sameProcess(read(expected.pid, expected.pid)))
                    throw new IOException(com.deepseekharness.app.util.UiText.text("终端组长身份已变化，停止回收"));
                File[] processes = new File("/proc").listFiles();
                if (processes == null) throw new IOException(com.deepseekharness.app.util.UiText.text("无法核验终端后台进程"));
                boolean active = false;
                for (File file : processes) {
                    if (!file.getName().matches("[1-9][0-9]*")) continue;
                    int pid;
                    try { pid = Integer.parseInt(file.getName()); } catch (NumberFormatException ignored) { continue; }
                    if (pid == expected.pid) continue;
                    if (!belongsToSession(pid, expected.pid)) continue;
                    try {
                        if (Os.stat(file.getPath()).st_uid != android.os.Process.myUid()) continue;
                    } catch (android.system.ErrnoException error) {
                        if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) continue;
                        throw new IOException(com.deepseekharness.app.util.UiText.text("无法核验终端进程归属"), error);
                    }
                    ProcessIdentity member = read(pid, expected.pid);
                    if (member == null || member.exited()) continue;
                    active = true;
                    signal(member, OsConstants.SIGKILL);
                }
                if (!active) break;
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException(com.deepseekharness.app.util.UiText.text("终端后台命令尚未退出，原环境保持保护"));
                Thread.sleep(20);
            }
            signal(leader, OsConstants.SIGKILL);
            ended = true;
        } finally {
            if (!ended) signal(leader, OsConstants.SIGCONT);
        }
    }

    /** 组长已自然退出时只核验剩余成员，不再向可能复用的 PID 发送信号。 */
    public static void requireSessionEmpty(int session) throws IOException {
        File[] processes = new File("/proc").listFiles();
        if (processes == null) throw new IOException(com.deepseekharness.app.util.UiText.text("无法核验终端后台进程"));
        for (File file : processes) {
            if (!file.getName().matches("[1-9][0-9]*")) continue;
            int pid;
            try { pid = Integer.parseInt(file.getName()); } catch (NumberFormatException ignored) { continue; }
            if (!belongsToSession(pid, session)) continue;
            ProcessIdentity member = read(pid, session);
            if (member != null && !member.exited())
                throw new IOException(com.deepseekharness.app.util.UiText.text("终端后台命令仍在退出，原环境保持保护；稍后可直接重试"));
        }
    }

    private static boolean belongsToSession(int pid, int session) throws IOException {
        int actual = NativeProcess.sessionId(pid);
        if (actual == -OsConstants.ESRCH) return false;
        if (actual < 0) throw new IOException(com.deepseekharness.app.util.UiText.text("无法查询终端会话（errno=") + -actual + com.deepseekharness.app.util.UiText.text("），请稍后重试"));
        return actual == session;
    }

    private static ProcessIdentity read(int pid, int session) throws IOException {
        long deadline=SystemClock.elapsedRealtime()+500;IOException failure=null;
        do {
            try {
                ProcessIdentity current=ProcessIdentity.inSession(Compat.readAll(new File("/proc/"+pid+"/stat")),pid,session);
                if(current!=null)return current;
                failure=new IOException(com.deepseekharness.app.util.UiText.text("终端进程身份暂不可读，保留环境保护"));
            }catch(IOException error){failure=error;}
            // exec/退出窗口内空读或不完整 stat 不是“进程已消失”。用独立内核会话查询核实。
            int actual=NativeProcess.sessionId(pid);
            if(actual==-OsConstants.ESRCH||actual>0&&actual!=session)return null;
            if(actual<0)throw new IOException(com.deepseekharness.app.util.UiText.text("无法查询终端会话（errno=")+-actual+com.deepseekharness.app.util.UiText.text("），请稍后重试"),failure);
            if(SystemClock.elapsedRealtime()>=deadline)break;
            try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("TERMINAL_IDENTITY_INTERRUPTED",interrupted);}
        }while(true);
        throw new IOException(com.deepseekharness.app.util.UiText.text("无法核验终端独立会话，原环境保持保护"),failure);
    }

    private static void signal(ProcessIdentity expected, int signal) throws IOException {
        ProcessIdentity current = read(expected.pid, expected.session);
        if (current == null || current.exited()) return;
        if (!expected.sameProcess(current)) throw new IOException(com.deepseekharness.app.util.UiText.text("终端进程身份已变化，未发送信号"));
        try { Os.kill(expected.pid, signal); }
        catch (android.system.ErrnoException error) {
            if (error.errno != OsConstants.ESRCH) throw new IOException(com.deepseekharness.app.util.UiText.text("终端进程回收失败"), error);
        }
    }
}
