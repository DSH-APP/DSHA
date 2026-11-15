package com.deepseekharness.app.core;

import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.WebProcSel;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/** Host preparation adapter; the ordered pipeline calls it, while the application owns the run. */
final class StartupEnvironmentActions implements StartupPipeline.Actions {
  private final HarnessController owner;
  private final HarnessSessionState state;
  private final WebProcessSession processes;
  private final long generation;
  private final Consumer<String> stage;
  private final Consumer<String> status;

  StartupEnvironmentActions(
      HarnessController owner,
      HarnessSessionState state,
      WebProcessSession processes,
      long generation,
      Consumer<String> stage,
      Consumer<String> status) {
    this.owner = owner;
    this.state = state;
    this.processes = processes;
    this.generation = generation;
    this.stage = stage;
    this.status = status;
  }

  public boolean current() {
    return state.lifecycle.isCurrent(generation);
  }

  public void yieldPluginQuery() {
    com.deepseekharness.app.plugins.PluginManagementEngine.get(owner.context()).yieldToUserAction();
  }

  public void stage(String value) {
    stage.accept(value);
  }

  public void status(String value) {
    status.accept(value);
  }

  public java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> releasePrior()
      throws IOException {
    if (!current()) return java.util.List.of();
    if (Thread.currentThread().isInterrupted())
      throw new java.io.InterruptedIOException("STARTUP_STOP_INTERRUPTED");
    java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> facts =
        new java.util.ArrayList<>(processes.stopOwnedLauncherFacts());
    if (!facts.isEmpty()) return java.util.List.copyOf(facts);
    if (!processes.confirmStopped())
      return java.util.List.of(
          com.deepseekharness.app.util.WebStopDiagnostic.of(
              com.deepseekharness.app.util.WebStopDiagnostic.Code.EXIT_UNCONFIRMED,
              "旧 Web 或容器启动器尚未确认退出"));
    processes.releasePrior(generation);
    return java.util.List.of();
  }

  /**
   * 启动前置停止的同一窗口只读复核：先确认退出，再补一次启动器收尾并释放旧代次；不重发信号。
   */
  public boolean awaitPriorExit(long deadlineMs) throws InterruptedException {
    java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> remaining;
    try {
      remaining =
          StartupStopWait.await(
              new StartupStopWait.Io() {
                public boolean confirmed() throws IOException {
                  return current() && processes.confirmStopped();
                }

                public long now() {
                  return android.os.SystemClock.elapsedRealtime();
                }

                public void pause(long millis) throws InterruptedException {
                  Thread.sleep(millis);
                }
              },
              deadlineMs,
              java.util.List.of());
      if (!remaining.isEmpty() || !current()) return false;
      // 屏障确认后仍要按原代次释放已知启动器与桥；任何一项未确认都不得继续启动。
      if (!processes.stopOwnedLauncherFacts().isEmpty()) return false;
      if (!processes.confirmStopped()) return false;
      processes.releasePrior(generation);
      return true;
    } catch (IOException unavailable) {
      return false;
    }
  }

  public long now() {
    return android.os.SystemClock.elapsedRealtime();
  }

  public void recoverData() throws Exception {
    com.deepseekharness.app.BackupManager.recoverInterrupted(owner);
  }

  public void cleanup() throws IOException {
    EnvironmentMaintenance.cleanupCompleted(owner);
  }

  public void beforeLaunch(String id) throws Exception {
    PluginActivationHooks.beforeLaunch(owner, id);
  }

  public String safeProfile() throws IOException {
    return StartupRepairs.prepareSafeProfile(owner);
  }

  public void checkpoint(String id) throws Exception {
    StartupRepairs.checkpoint(
        owner, new org.json.JSONObject().put("command", "prepare").put("startupId", id));
  }

  public boolean unlockStartup() throws IOException {
    synchronized (state.lifecycle) {
      if (!current()) return false;
      File root = owner.proot().getRootfsDir();
      File sentinel = new File(root, WebProcSel.pidFileRel(WebProcSel.STOP_SENTINEL));
      if (sentinel.exists() && !sentinel.delete())
        throw new IOException(com.deepseekharness.app.util.UiText.text("无法清除停止标记"));
      try {
        Compat.write(new File(root, "root/dsh-web.log"), new byte[0]);
      } catch (IOException error) {
        state.diagnostics.message(
            generation, "WEB_LOG_UNAVAILABLE:" + error.getClass().getSimpleName());
      }
      return true;
    }
  }
}
