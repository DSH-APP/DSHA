package com.deepseekharness.app;

import java.lang.ref.WeakReference;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/**
 * 容器里的一个真 PTY 会话：把容器运行时 + bash 挂在伪终端上，让 vim / htop / tmux 跑得起来。
 *
 * <p><b>为什么需要它</b>：旧终端是「{@code execRootfsInteractive()} 起 bash，把 stdout 塞进
 * TextView」。没有 PTY 就没有 {@code TERM}、没有行编辑、没有光标定位 —— 任何 TUI 程序要么
 * 直接报 not a tty，要么画出一屏乱码。这是用户一上手就会撞到的硬伤。
 *
 * <p><b>PTY 与终端模拟没有自己写</b>：用的是 Termux 官方拆出来的 terminal-emulator /
 * terminal-view。这两个子模块是 <b>Apache 2.0</b>（termux-app 整体 GPLv3，但它们不是），
 * 与本项目的 MIT 兼容；aar 里自带 {@code jni/arm64-v8a/libtermux.so}，不需要装 Termux，
 * 里面就是 openpty + fork + execvp 那一套，省掉自己编 pty-bridge。
 *
 * <p><b>启动参数只有一个来源</b>：{@link ProotBootstrap#ptyArgv} / {@link ProotBootstrap#ptyEnv}
 * ——与 {@code execRootfs} 共用同一份构造逻辑。各写一份的话，PTY 里的 shell 会跑在和普通
 * 命令不一样的环境里（少个 PROOT_LOADER 就直接起不来），而这个项目已经在「同一份判断
 * 散落两处」上栽过四次。
 *
 * <p>这个类只管会话与回调转发，不碰任何 View —— UI 侧实现 {@link Listener} 即可，
 * 不必去实现 TerminalSessionClient 那 17 个方法。
 */
public final class PtySession implements TerminalSessionClient {

    private static final String TAG = "DSHA-pty";

    /** 回滚缓冲行数：够往上翻几屏日志，又不至于把内存吃掉（每行 80 列约 200 字节）。 */
    private static final int TRANSCRIPT_ROWS = 2000;

    /** UI 侧只关心这几件事。回调都在 PTY 读线程上来，实现方自己 post 到主线程。 */
    public interface Listener {
        /** 屏幕内容变了 → 该重绘。 */
        void onOutput();

        /** 标题变了（PS1 与 tmux 都会改），可以显示在标题栏。 */
        void onTitle(String title);

        /** 会话结束（exit / 进程被杀）。 */
        void onExit(int status);

        /** 终端要求把选中内容放进剪贴板。 */
        void onCopy(String text);

        /** 终端要求粘贴（比如 bracketed paste）。 */
        void onPasteRequest();

        /** 响铃：震一下比响一声合适。 */
        void onBell();
    }

    private volatile WeakReference<Listener> listener = new WeakReference<>(null);
    private volatile TerminalSession session;
    private com.deepseekharness.app.util.ProcessIdentity identity;
    private com.deepseekharness.app.core.RuntimeTasks work;
    private volatile boolean maintenanceClosing;
    private volatile boolean maintenanceUncertain;

    private PtySession() {
    }

    public void attachListener(Listener l) {
        listener = new WeakReference<>(l);
    }

    public void detachListener(Listener l) {
        Listener current = listener.get();
        if (current == l) listener = new WeakReference<>(null);
    }

    private Listener listener() {
        return listener.get();
    }

    /**
     * 起一个会话。
     *
     * @param cols 列数，@param rows 行数 —— 必须是按控件实测字宽算出来的，
     *             瞎给一个值会让 TUI 的边框错位。
     */
    public static PtySession start(ProotBootstrap proot, int cols, int rows, Listener l) {
        PtySession ps = new PtySession();
        ps.attachListener(l);
        // 必须先登记异步寿命，再准备目录和 fork，避免维护切换与终端启动交错。
        ps.work = com.deepseekharness.app.core.RuntimeTasks.beginDetached();
        try {
        proot.ensureAndroidGroups(); // 登录 shell 的 $(groups) 依赖 /etc/group 里有 Android GID
        String[] argv = proot.ptyArgv();
        String[] env = proot.ptyEnv();
        // args 就是 argv（含 argv[0]）：查过 termux.c，Java 数组原样转成 argv 后
        // 直接 execvp(cmd, argv)，没有任何加工 —— 与 ProcessBuilder 的行为一致。
        TerminalSession s = new TerminalSession(argv[0], "/", argv, env, TRANSCRIPT_ROWS, ps);
        ps.session = s;
        // initializeEmulator 才真正 fork 出子进程，所以尺寸要在这之前定好
        s.initializeEmulator(Math.max(4, cols), Math.max(2, rows));
        ps.identity = readIdentity(s.getPid());
        return ps;
        } catch (RuntimeException | Error e) {
            if (ps.session == null || !ps.session.isRunning()) ps.work.close();
            else {
                // fork 已成功时，退出回调才能释放工作锁；初始化异常不能先放行环境维护。
                try { ps.finish(); } catch (RuntimeException ignored) { }
            }
            throw e;
        }
    }

    public TerminalSession session() {
        return session;
    }

    public void write(String s) {
        TerminalSession t = session;
        if (t != null && s != null && !s.isEmpty()) t.write(s);
    }

    public void resize(int cols, int rows) {
        TerminalSession t = session;
        if (t != null) t.updateSize(Math.max(4, cols), Math.max(2, rows));
    }

    public boolean isRunning() {
        TerminalSession t = session;
        return t != null && t.isRunning();
    }

    public void finishAndWait(long timeoutMs) throws java.io.IOException, InterruptedException {
        if (maintenanceUncertain) throw new java.io.IOException("上次终端回收未能确认，环境保护仍保留；请重新打开 App 后重试");
        maintenanceClosing = true;
        try {
        if (isRunning()) {
            com.deepseekharness.app.util.ProcessIdentity expected = identity;
            if (expected == null) throw new java.io.IOException("无法确认终端身份，原环境保持保护");
            try {
                String executable = android.system.Os.readlink("/proc/" + expected.pid + "/exe");
                if (com.deepseekharness.app.util.ProcessIdentity.isProot(executable)) finish();
                else com.deepseekharness.app.runtime.TerminalProcessCloser.close(expected, timeoutMs);
            } catch (android.system.ErrnoException error) { throw new java.io.IOException("无法确认终端可执行文件", error); }
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (isRunning() && android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(20);
        if (isRunning())
            throw new java.io.IOException("终端仍在退出，已保留原环境；请稍后重试");
        if (work != null) work.close();
        } catch (java.io.IOException | InterruptedException | RuntimeException error) {
            maintenanceUncertain = true;
            throw error;
        }
    }

    /** 结束会话（切页面/退出时调，避免留一个孤儿 bash 在容器里跑）。 */
    public void finish() {
        TerminalSession t = session;
        if (t == null || !t.isRunning()) return;
        int pid = t.getPid();
        try {
            com.deepseekharness.app.util.ProcessIdentity expected = identity;
            if (expected == null) expected = readIdentity(pid);
            if (expected == null || !expected.sameProcess(readIdentity(pid)))
                throw new IllegalStateException("无法确认本次终端进程身份，尚未停止");
            String executable = android.system.Os.readlink("/proc/" + pid + "/exe");
            // proot 的 SIGQUIT 会先清理自己登记的全部 tracee；直接 KILL 会遗留后台 shell/命令。
            // 普通交互 shell 用 HUP，让 shell 将挂断通知传给自己的作业。
            int signal = com.deepseekharness.app.util.ProcessIdentity.isProot(executable)
                    ? android.system.OsConstants.SIGQUIT : android.system.OsConstants.SIGHUP;
            if (!expected.sameProcess(readIdentity(pid))) throw new IllegalStateException("终端进程身份已变化，未发送信号");
            android.system.Os.kill(pid, signal);
        } catch (android.system.ErrnoException e) {
            if (e.errno != android.system.OsConstants.ESRCH)
                throw new IllegalStateException("终端停止失败，后台任务仍保留保护", e);
        }
        // 不在主线程等待 JNI 回调，也不提前释放 RuntimeTasks。
    }

    private static com.deepseekharness.app.util.ProcessIdentity readIdentity(int pid) {
        try {
            return com.deepseekharness.app.util.ProcessIdentity.fromStat(
                    com.deepseekharness.app.util.Compat.readAll(new java.io.File("/proc/" + pid + "/stat")),
                    pid, android.os.Process.myPid());
        } catch (java.io.IOException | RuntimeException error) { return null; }
    }

    // ==================== TerminalSessionClient ====================

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        Listener l = listener();
        if (l != null) l.onOutput();
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        Listener l = listener();
        if (l != null) l.onTitle(changedSession == null ? "" : changedSession.getTitle());
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        if (!maintenanceClosing) {
            if (work != null) work.close();
        }
        Listener l = listener();
        if (l != null) l.onExit(finishedSession == null ? -1 : finishedSession.getExitStatus());
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        Listener l = listener();
        if (l != null) l.onCopy(text);
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        Listener l = listener();
        if (l != null) l.onPasteRequest();
    }

    @Override
    public void onBell(TerminalSession session) {
        Listener l = listener();
        if (l != null) l.onBell();
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
        Listener l = listener();
        if (l != null) l.onOutput();
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        // 光标闪烁状态变化：重绘由 TerminalView 自己安排，这里不用管
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null;    // null = 用库的默认（块状光标）
    }

    // ---- 日志：库里打得很细，统一收口到 logcat，出问题时能看到 PTY 层的动静 ----

    @Override
    public void logError(String tag, String message) {
        android.util.Log.e(TAG, SensitiveData.redact(tag + ": " + message));
    }

    @Override
    public void logWarn(String tag, String message) {
        android.util.Log.w(TAG, SensitiveData.redact(tag + ": " + message));
    }

    @Override
    public void logInfo(String tag, String message) {
        android.util.Log.i(TAG, SensitiveData.redact(tag + ": " + message));
    }

    @Override
    public void logDebug(String tag, String message) {
        android.util.Log.d(TAG, SensitiveData.redact(tag + ": " + message));
    }

    @Override
    public void logVerbose(String tag, String message) {
        // Verbose 里是每个字节的读写，开着会把 logcat 冲垮 —— 故意丢掉
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        android.util.Log.w(TAG, SensitiveData.redact(tag + ": " + message
                + " " + String.valueOf(e)));
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        android.util.Log.w(TAG, SensitiveData.redact(tag + ": " + String.valueOf(e)));
    }
}
