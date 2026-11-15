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

  /** UI 侧只关心这几件事；Termux 通过主线程 Handler 分发终端事件。 */
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

    /** 输入未接收或写入结果不确定；内容不会自动重放。 */
    default void onInputProblem(int reason) {}
  }

  private volatile WeakReference<Listener> listener = new WeakReference<>(null);
  private volatile TerminalSession session;
  private volatile com.deepseekharness.app.util.ProcessIdentity identity;
  private com.deepseekharness.app.core.RuntimeTasks work;
  private volatile boolean maintenanceClosing;
  private volatile String cleanupError = "";
  private volatile Integer exitStatus;

  /**
   * 监听器与输入问题状态的独立锁。finishAndWait 持有会话监视器最长数秒等待进程退出；
   * 主线程切换 / 重绘时注册监听、读取输入问题不得排在它后面，否则关闭标签即 ANR，
   * 用户选择「关闭应用」会连同 DSH 环境一起结束。
   */
  private final Object inputState = new Object();

  private int pendingInputProblem;
  private Runnable cleanupVerified, cleanupChanged;
  private boolean prootLauncher;
  private static final java.util.Set<PtySession> pending =
      java.util.Collections.newSetFromMap(
          new java.util.concurrent.ConcurrentHashMap<PtySession, Boolean>());

  private PtySession() {}

  public void attachListener(Listener l) {
    int problem;
    synchronized (inputState) {
      listener = new WeakReference<>(l);
      problem = pendingInputProblem;
    }
    if (l != null && problem != 0) l.onInputProblem(problem);
  }

  public int inputProblem() {
    synchronized (inputState) {
      return pendingInputProblem;
    }
  }

  public void acknowledgeInputProblem(int reason) {
    synchronized (inputState) {
      if (pendingInputProblem == reason) pendingInputProblem = 0;
    }
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
    return start(proot, cols, rows, l, null, null, null, null);
  }

  /** 只增加已核验释放通知，fork/出生身份/guest 退出算法保持原样。 */
  public static PtySession start(
      ProotBootstrap proot,
      int cols,
      int rows,
      Listener l,
      Runnable cleanupVerified,
      Runnable cleanupChanged,
      Runnable beforeFork,
      java.util.function.Consumer<PtySession> allocated) {
    return start(
        proot, cols, rows, l, cleanupVerified, cleanupChanged, beforeFork, allocated, null);
  }

  /** 固定 guest command 只替换命令尾部；fork、出生身份、保护票和失败清理保持原样。 */
  public static PtySession start(
      ProotBootstrap proot,
      int cols,
      int rows,
      Listener l,
      Runnable cleanupVerified,
      Runnable cleanupChanged,
      Runnable beforeFork,
      java.util.function.Consumer<PtySession> allocated,
      String[] guestCommand) {
    PtySession ps = new PtySession();
    ps.cleanupVerified = cleanupVerified;
    ps.cleanupChanged = cleanupChanged;
    ps.attachListener(l);
    try {
      if (allocated != null) allocated.accept(ps);
      // 必须先登记异步寿命，再准备目录和 fork，避免维护切换与终端启动交错。
      ps.work = com.deepseekharness.app.core.RuntimeTasks.beginDetached("终端");
      pending.add(ps);
      // 共享租约只覆盖创建/fork 窗口；终端寿命仍由上面的 RuntimeTasks token 与退出核验持有。
      com.deepseekharness.app.util.EnvironmentTaskGate.Lease environmentUse =
          com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquireShared("启动终端");
      if (environmentUse == null)
        throw new IllegalStateException(
            com.deepseekharness.app.util.UiText.text("环境维护或独占任务正在进行，终端未启动"));
      try {
        try {
          proot.requireUserRuntime();
        } catch (java.io.IOException error) {
          throw new IllegalStateException(error.getMessage(), error);
        }
        proot.ensureAndroidGroups(); // 登录 shell 的 $(groups) 依赖 /etc/group 里有 Android GID
        ProotBootstrap.PtyLaunch launch = proot.ptyLaunch(guestCommand);
        String[] argv = launch.argv;
        ps.prootLauncher = com.deepseekharness.app.util.ProcessIdentity.isProot(argv[0]);
        String[] env = launch.environment;
        // args 就是 argv（含 argv[0]）：查过 termux.c，Java 数组原样转成 argv 后
        // 直接 execvp(cmd, argv)，没有任何加工 —— 与 ProcessBuilder 的行为一致。
        initializeOnMain(
            () -> {
              // 0.118.0 的 TerminalSession 内建 Handler 绑定构造线程；只能在主 Looper 构造/初始化。
              // 资产准备留在工作线程，主线程只完成原有终端构造与出生握手。
              if (beforeFork != null) beforeFork.run();
              TerminalSession s = new TerminalSession(argv[0], "/", argv, env, TRANSCRIPT_ROWS, ps);
              ps.session = s;
              // initializeEmulator 才真正 fork 出子进程，所以尺寸要在这之前定好。
              com.deepseekharness.app.runtime.NativeProcess.capturePtyIdentity(
                  value -> ps.identity = value,
                  () -> s.initializeEmulator(Math.max(4, cols), Math.max(2, rows)));
            });
        return ps;
      } finally {
        environmentUse.close();
      }
    } catch (RuntimeException | Error e) {
      if (ps.session == null || (ps.session.getPid() == 0 && ps.identity == null)) ps.releaseWork();
      else if (ps.session.getPid() == 0) {
        // 登记后的 JNI 异常也要确认会话已空；不能仅因 Java 尚未收到 PID 就释放。
        try {
          com.deepseekharness.app.runtime.TerminalProcessCloser.requireSessionEmpty(
              ps.identity.pid);
          ps.releaseWork();
        } catch (java.io.IOException | RuntimeException uncertain) {
          ps.cleanupError = SensitiveData.redact(String.valueOf(uncertain.getMessage()));
          ps.notifyCleanupChanged();
          android.util.Log.w(
              TAG, com.deepseekharness.app.util.UiText.text("终端初始化异常后仍无法确认退出，已保留环境保护"));
        }
      } else {
        // fork 已成功时，退出回调才能释放工作锁；初始化异常不能先放行环境维护。
        try {
          ps.finish();
        } catch (RuntimeException ignored) {
        }
      }
      throw e;
    }
  }

  private static void initializeOnMain(Runnable initialize) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      initialize.run();
      return;
    }
    java.util.concurrent.FutureTask<Void> task =
        new java.util.concurrent.FutureTask<>(initialize, null);
    if (!new android.os.Handler(android.os.Looper.getMainLooper()).post(task))
      throw new IllegalStateException("TERMINAL_MAIN_DISPATCH_REJECTED");
    boolean interrupted = false;
    try {
      while (true) {
        try {
          task.get();
          return;
        } catch (InterruptedException waitAgain) {
          // 不能取消可能已 fork 的 UI 回调；等待确切回执后再恢复中断标记。
          interrupted = true;
        } catch (java.util.concurrent.ExecutionException failed) {
          Throwable cause = failed.getCause();
          if (cause instanceof Error) throw (Error) cause;
          if (cause instanceof RuntimeException) throw (RuntimeException) cause;
          throw new IllegalStateException("TERMINAL_MAIN_INITIALIZATION_FAILED", cause);
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
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

  public static void closeAllForMaintenance(long timeoutMs)
      throws java.io.IOException, InterruptedException {
    for (PtySession terminal : pending.toArray(new PtySession[0]))
      terminal.finishAndWait(timeoutMs);
  }

  /** 与 isRunning 分离：pending 只有原有退出核验成功后才能移除。 */
  public boolean isCleanupPending() {
    return pending.contains(this);
  }

  public String cleanupError() {
    return cleanupError;
  }

  public Integer exitStatus() {
    return exitStatus;
  }

  private synchronized void releaseWork() {
    if (work != null) work.close();
    pending.remove(this);
    cleanupError = "";
    Runnable released = cleanupVerified;
    cleanupVerified = null;
    if (released != null) {
      try {
        released.run();
      } catch (RuntimeException ignored) {
      }
    }
    notifyCleanupChanged();
  }

  private void notifyCleanupChanged() {
    Runnable changed = cleanupChanged;
    if (changed != null) {
      try {
        changed.run();
      } catch (RuntimeException ignored) {
      }
    }
  }

  public synchronized void finishAndWait(long timeoutMs)
      throws java.io.IOException, InterruptedException {
    // 已核验并释放的历史标签可重复关闭，不重新查询可能已复用的进程编号。
    if (!pending.contains(this)) return;
    maintenanceClosing = true;
    try {
      if (isRunning()) {
        com.deepseekharness.app.util.ProcessIdentity expected = identity;
        if (expected == null)
          throw new java.io.IOException(
              com.deepseekharness.app.util.UiText.text("无法确认终端身份，原环境保持保护"));
        TerminalSession active = session;
        if (active != null) active.cancelPendingInput();
        // 启动方式在 fork 前由可信 argv 记录，PID 身份仍用出生握手与当前 stat 双重核验。
        // /proc/PID/exe 在退出及非调试应用 exec 窗口可能不可读，不能把它当成永久维护故障。
        if (prootLauncher)
          com.deepseekharness.app.runtime.TerminalProcessCloser.closeProot(expected);
        else com.deepseekharness.app.runtime.TerminalProcessCloser.close(expected, timeoutMs);
      }
      long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
      while (isRunning() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
      if (isRunning())
        throw new java.io.IOException(
            com.deepseekharness.app.util.UiText.text("终端仍在退出，已保留原环境；请稍后重试"));
      // JNI 退出只证明启动器结束；proroot 的 guest 也必须全部退出。
      if (identity == null)
        throw new java.io.IOException(
            com.deepseekharness.app.util.UiText.text("无法确认终端会话身份，原环境保持保护"));
      com.deepseekharness.app.runtime.TerminalProcessCloser.requireSessionEmpty(identity.pid);
      releaseWork();
    } catch (java.io.IOException | InterruptedException | RuntimeException error) {
      cleanupError = SensitiveData.redact(String.valueOf(error.getMessage()));
      notifyCleanupChanged();
      android.util.Log.w(
          TAG,
          com.deepseekharness.app.util.UiText.format(
              "终端回收暂未确认，下次维护将重新核验：%s", SensitiveData.redact(String.valueOf(error))));
      throw error;
    }
  }

  /** 显式请求结束会话；切页面、Activity 销毁均不得调用。 */
  public void finish() {
    TerminalSession t = session;
    if (t == null || !t.isRunning()) return;
    int pid = t.getPid();
    try {
      com.deepseekharness.app.util.ProcessIdentity expected = identity;
      if (expected == null || !expected.sameProcess(readIdentity(pid)))
        throw new IllegalStateException(
            com.deepseekharness.app.util.UiText.text("无法确认本次终端进程身份，尚未停止"));
      String executable = android.system.Os.readlink("/proc/" + pid + "/exe");
      // proot 的 SIGQUIT 会先清理自己登记的全部 tracee；直接 KILL 会遗留后台 shell/命令。
      // 普通交互 shell 用 HUP，让 shell 将挂断通知传给自己的作业。
      int signal =
          com.deepseekharness.app.util.ProcessIdentity.isProot(executable)
              ? android.system.OsConstants.SIGQUIT
              : android.system.OsConstants.SIGHUP;
      if (!expected.sameProcess(readIdentity(pid)))
        throw new IllegalStateException(
            com.deepseekharness.app.util.UiText.text("终端进程身份已变化，未发送信号"));
      t.cancelPendingInput();
      android.system.Os.kill(pid, signal);
    } catch (android.system.ErrnoException e) {
      if (e.errno != android.system.OsConstants.ESRCH)
        throw new IllegalStateException(
            com.deepseekharness.app.util.UiText.text("终端停止失败，后台任务仍保留保护"), e);
    }
    // 不在主线程等待 JNI 回调，也不提前释放 RuntimeTasks。
  }

  private static com.deepseekharness.app.util.ProcessIdentity readIdentity(int pid) {
    try {
      return com.deepseekharness.app.util.ProcessIdentity.fromStat(
          com.deepseekharness.app.util.Compat.readAll(new java.io.File("/proc/" + pid + "/stat")),
          pid,
          android.os.Process.myPid());
    } catch (java.io.IOException | RuntimeException error) {
      return null;
    }
  }

  // ==================== TerminalSessionClient ====================

  @Override
  public void onInputError(TerminalSession source, int reason) {
    if (source != session) return;
    Listener l;
    int problem;
    synchronized (inputState) {
      l = listener();
      pendingInputProblem = Math.max(pendingInputProblem, reason);
      problem = pendingInputProblem;
    }
    if (l != null) l.onInputProblem(problem);
  }

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
    exitStatus = finishedSession == null ? null : finishedSession.getExitStatus();
    notifyCleanupChanged();
    if (!maintenanceClosing) {
      // 保留待核验会话，避免页面回调清掉引用后无法再次回收。
      Thread verify =
          new Thread(
              () -> {
                try {
                  finishAndWait(3000);
                } catch (java.io.IOException | InterruptedException | RuntimeException error) {
                  if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                }
              },
              "dsha-pty-exit");
      verify.setDaemon(true);
      verify.start();
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
    return null; // null = 用库的默认（块状光标）
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
    android.util.Log.w(TAG, SensitiveData.redact(tag + ": " + message + " " + String.valueOf(e)));
  }

  @Override
  public void logStackTrace(String tag, Exception e) {
    android.util.Log.w(TAG, SensitiveData.redact(tag + ": " + String.valueOf(e)));
  }
}
