package com.deepseekharness.app.runtime;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessIdentity;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.ProcessBirthHandshake;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** 冷安装及有界 proroot 命令的独立会话监督器；关闭前核验本次整组退出。 */
final class IsolatedInstallProcess extends Process implements AutoCloseable {
  private final Process supervisor;
  private final ProcessIdentity identity;
  private final File status;
  private final BoundedGuestSessions.Operation record;
  private volatile boolean closing, closed, recordRetired;

  interface ExitEvidence {
    boolean groupGone();

    boolean sessionEmpty();
  }

  private final ExitEvidence evidence;

  static boolean supported(android.content.Context context) {
    return sessionLauncher(context) != null;
  }

  static String sessionLauncher(android.content.Context context) {
    String bundled = bundledLauncher(context);
    if (bundled != null) return bundled;
    File system = new File("/system/bin/setsid");
    return system.canExecute() ? system.getAbsolutePath() : null;
  }

  static String bundledLauncher(android.content.Context context) {
    File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libdsha-session.so");
    return binary.isFile() && binary.length() > 0 ? binary.getAbsolutePath() : null;
  }

  static IsolatedInstallProcess start(
      ProcessBuilder target, File temporary, android.content.Context context) throws IOException {
    return start(target, temporary, context, null);
  }

  static IsolatedInstallProcess start(
      ProcessBuilder target,
      File temporary,
      android.content.Context context,
      BoundedGuestSessions.Operation record)
      throws IOException {
    String launcher = record == null ? sessionLauncher(context) : bundledLauncher(context);
    if (launcher == null)
      throw new IOException(com.deepseekharness.app.util.UiText.text("此系统不支持独立安装进程组"));
    File status = new File(temporary, "cold-install-" + java.util.UUID.randomUUID() + ".status");
    // 完成后仍保留组长，直到宿主核验身份并回收，避免 PID/进程组号复用的歧义。
    boolean nativeLauncher = !launcher.equals("/system/bin/setsid");
    String shell =
        com.deepseekharness.app.util.IsolatedCommand.script(
            target.command(), status.getAbsolutePath(), !nativeLauncher);
    target.command(
        nativeLauncher
            ? Arrays.asList(launcher, "--birth-handshake", "/system/bin/sh", "-c", shell)
            : Arrays.asList(launcher, "/system/bin/sh", "-c", shell));
    // 调用方保留 ProcessBuilder 默认 PIPE，避免依赖 API 26 的 Redirect。
    Process supervisor = target.start();
    boolean accepted = false;
    IsolatedInstallProcess ready = null;
    try {
      ProcessIdentity birth =
          ProcessBirthHandshake.read(
              supervisor.getInputStream(),
              android.os.Process.myPid(),
              () -> !ProcessTermination.exited(supervisor),
              2000);
      ProcessIdentity identity = readIdentity(birth.pid);
      if (birth.sameProcess(identity) && identity.ownsSession()) {
        ready = new IsolatedInstallProcess(supervisor, identity, status, record);
        if (record != null) record.beforeLaunch(identity);
        supervisor
            .getOutputStream()
            .write("DSHA_START\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        supervisor.getOutputStream().flush();
        accepted = true;
        return ready;
      }
      throw new IOException("PROCESS_BIRTH_RECHECK_UNCONFIRMED");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException(com.deepseekharness.app.util.UiText.text("安装准备被中断"), error);
    } finally {
      // 握手前只等 stdin，没有创建 guest 子进程；握手写入失败也回收整个已核验的组。
      if (!accepted) {
        if (ready != null) ready.close();
        else Compat.destroy(supervisor);
        status.delete();
      }
    }
  }

  private IsolatedInstallProcess(
      Process supervisor,
      ProcessIdentity identity,
      File status,
      BoundedGuestSessions.Operation record) {
    this.supervisor = supervisor;
    this.identity = identity;
    this.status = status;
    this.record = record;
    evidence = null;
  }

  IsolatedInstallProcess(
      Process supervisor, ProcessIdentity identity, File status, ExitEvidence evidence) {
    this.supervisor = supervisor;
    this.identity = identity;
    this.status = status;
    this.record = null;
    this.evidence = evidence;
  }

  private static ProcessIdentity readIdentity(int pid) {
    if (pid <= 1) return null;
    try {
      return ProcessIdentity.fromStat(
          Compat.readAll(new File("/proc/" + pid + "/stat")), pid, android.os.Process.myPid());
    } catch (IOException | RuntimeException error) {
      return null;
    }
  }

  private synchronized Integer poll() {
    if (closing
        && !closed
        && groupGone()
        && ProcessTermination.exited(supervisor)
        && boundedSessionEmpty()) {
      return supervisor.exitValue();
    }
    if (closed) return supervisor.exitValue();
    if (!closing && status.isFile()) {
      try {
        int code = com.deepseekharness.app.util.IsolatedCommand.status(Compat.readAll(status));
        if (code >= 0) return code;
      } catch (IOException ignored) {
      }
    }
    if (!closing && ProcessTermination.exited(supervisor) && groupGone() && boundedSessionEmpty()) {
      // Native leader ends the group if its shell exits before publishing status.
      // No status is never successful, even if a shell returned zero.
      int code = supervisor.exitValue();
      return code == 0 ? 125 : code;
    }
    return null;
  }

  @Override
  public int exitValue() {
    Integer value = poll();
    if (value != null) return value;
    throw new IllegalThreadStateException(com.deepseekharness.app.util.UiText.text("安装进程组尚未结束"));
  }

  @Override
  public boolean isAlive() {
    return poll() == null;
  }

  @Override
  public int waitFor() throws InterruptedException {
    long delay = 10;
    while (true) {
      try {
        return exitValue();
      } catch (IllegalThreadStateException running) {
        Thread.sleep(delay);
        delay = Math.min(100, delay * 2);
      }
    }
  }

  @Override
  public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit)
      throws InterruptedException {
    long started = System.nanoTime(), budget = unit.toNanos(timeout), delay = 10;
    do {
      if (poll() != null) return true;
      long remaining = budget - (System.nanoTime() - started);
      if (remaining <= 0) return false;
      java.util.concurrent.TimeUnit.NANOSECONDS.sleep(
          Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay)));
      delay = Math.min(100, delay * 2);
    } while (true);
  }

  @Override
  public InputStream getInputStream() {
    return supervisor.getInputStream();
  }

  @Override
  public InputStream getErrorStream() {
    return supervisor.getErrorStream();
  }

  @Override
  public OutputStream getOutputStream() {
    return supervisor.getOutputStream();
  }

  @Override
  public void destroy() {
    close();
  }

  @Override
  public Process destroyForcibly() {
    close();
    return this;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      retireRecord();
      return;
    }
    closing = true;
    ProcessIdentity current = readIdentity(identity.pid);
    if (!identity.sameProcess(current) || !current.ownsSession()) {
      if (ProcessTermination.exited(supervisor) && groupGone() && boundedSessionEmpty()) {
        closed = true;
        status.delete();
        retireRecord();
        return;
      }
      throw new IllegalStateException(
          com.deepseekharness.app.util.UiText.text("安装进程组身份不可确认，已停止后续维护"));
    }
    try {
      Os.kill(-identity.pid, OsConstants.SIGKILL);
    } catch (android.system.ErrnoException error) {
      throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("无法回收安装进程组"), error);
    }
    if (!ProcessTermination.awaitExit(supervisor, 3000))
      throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("安装监督进程未退出"));
    boolean interrupted = Thread.interrupted();
    long deadline = SystemClock.elapsedRealtime() + 3000;
    try {
      while (!groupGone() && SystemClock.elapsedRealtime() < deadline) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    if (!groupGone())
      throw new IllegalStateException(
          com.deepseekharness.app.util.UiText.text("安装子进程尚未完全退出，已暂停后续维护"));
    if (!boundedSessionEmpty())
      throw new IllegalStateException("BOUNDED_GUEST_SESSION_UNCONFIRMED");
    closed = true;
    status.delete();
    retireRecord();
  }

  private void retireRecord() {
    if (record == null || recordRetired) return;
    try {
      record.completed();
      recordRetired = true;
    } catch (IOException failure) {
      throw new IllegalStateException("BOUNDED_GUEST_RECORD_RETAINED", failure);
    }
  }

  void markRecordUncertain() {
    if (record != null) record.uncertain();
  }

  private boolean groupGone() {
    if (evidence != null) return evidence.groupGone();
    try {
      Os.kill(-identity.pid, 0);
      return false;
    } catch (android.system.ErrnoException error) {
      return error.errno == OsConstants.ESRCH;
    }
  }

  private boolean boundedSessionEmpty() {
    if (evidence != null) return evidence.sessionEmpty();
    if (record == null) return true;
    try {
      TerminalProcessCloser.requireSessionEmpty(identity.pid);
      return true;
    } catch (IOException unknown) {
      return false;
    }
  }
}
