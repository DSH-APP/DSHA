package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.ProcessIdentity;

/** 查询内核会话号，并在 PTY 的 fork/exec 之间登记子进程自读的出生身份。 */
public final class NativeProcess {
    static { System.loadLibrary("termux"); }
    private static final ThreadLocal<java.util.function.Consumer<ProcessIdentity>> ptyCapture = new ThreadLocal<>();
    private NativeProcess() { }
    /** 成功返回会话号，失败返回负 errno。 */
    static native int sessionId(int pid);

    /** JNI 在本线程同步回调；身份先交给会话，再允许子进程 exec 和启动 waitFor 线程。 */
    public static void capturePtyIdentity(java.util.function.Consumer<ProcessIdentity> receiver, Runnable initialize) {
        if (ptyCapture.get() != null) throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("终端身份登记不能嵌套"));
        ptyCapture.set(receiver);
        try { initialize.run(); }
        finally { ptyCapture.remove(); }
    }

    @androidx.annotation.Keep
    private static boolean recordPtyIdentity(int pid, String stat) {
        java.util.function.Consumer<ProcessIdentity> receiver = ptyCapture.get();
        ProcessIdentity identity = ProcessIdentity.fromStat(stat, pid, android.os.Process.myPid());
        if (receiver == null || identity == null || !identity.ownsSession()) return false;
        receiver.accept(identity);
        return true;
    }
}
