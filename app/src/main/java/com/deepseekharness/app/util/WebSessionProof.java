package com.deepseekharness.app.util;

/** 只读识别已保存的Web实例；不授予停止或业务重放权限。 */
public final class WebSessionProof {
  private WebSessionProof() {}

  public record Process(
      int pid,
      long started,
      int uid,
      String command,
      String generation,
      String instance,
      String nonce,
      boolean alive) {}

  public record Saved(
      String boot,
      long generation,
      String instance,
      String nonce,
      int webPid,
      long webStarted,
      String webCommand,
      int launcherPid,
      long launcherStarted,
      String launcherCommand) {}

  public static boolean matches(Saved saved, String boot, int uid, Process web, Process launcher) {
    if (saved == null
        || saved.boot() == null
        || !saved.boot().equals(boot)
        || saved.generation() < 1
        || saved.generation() == Long.MAX_VALUE
        || saved.instance() == null
        || !saved.instance().matches(Ids.UUID_PATTERN)
        || saved.nonce() == null
        || !saved.nonce().matches("[a-f0-9]{64}")
        || saved.webPid() <= 1
        || saved.webStarted() <= 0
        || saved.launcherPid() <= 1
        || saved.launcherStarted() <= 0
        || web == null
        || !web.alive()
        || web.uid() != uid
        || web.pid() != saved.webPid()
        || web.started() != saved.webStarted()
        || !web.command().equals(saved.webCommand())
        || !WebProcSel.maySignalWeb(web.command())
        || !WebProcSel.trialProfile(web.command()).isEmpty()
        || !identity(saved, web)
        || launcher == null
        || launcher.pid() != saved.launcherPid()) return false;
    // 启动器退出不代表guest退出；包括proroot脱离的后代，仍须通过上面的完整活态证据。
    return !launcher.alive()
        || launcher.uid() == uid
            && launcher.pid() == saved.launcherPid()
            && launcher.started() == saved.launcherStarted()
            && launcher.command().equals(saved.launcherCommand())
            && identity(saved, launcher);
  }

  private static boolean identity(Saved saved, Process process) {
    return Long.toString(saved.generation()).equals(process.generation())
        && saved.instance().equals(process.instance())
        && saved.nonce().equals(process.nonce());
  }
}
