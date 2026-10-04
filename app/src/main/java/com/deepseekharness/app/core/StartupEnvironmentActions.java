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

  public void stage(String value) {
    stage.accept(value);
  }

  public void status(String value) {
    status.accept(value);
  }

  public void releasePrior() {
    processes.releasePrior(generation);
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
