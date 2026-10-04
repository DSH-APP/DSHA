package com.deepseekharness.app.util;

/** The exact Web shell preamble, with a captured launch generation and no credentials in argv. */
public final class WebLaunchCommand {
  private WebLaunchCommand() {}

  public static String build(
      String permissionMode,
      boolean confirmShell,
      String language,
      String profile,
      int listenPort,
      long generation) {
    if (listenPort < 0 || listenPort > 65535 || generation < 0)
      throw new IllegalArgumentException("WEB_LAUNCH_IDENTITY");
    return "export DSH_HOME=/root/.dsh && "
        + "export DSHA_WORKSPACE_DOCUMENTS=/root/Documents && "
        + "export DSH_PERMISSION_MODE="
        + ShellQuote.arg(permissionMode)
        + " && "
        + "export DSH_CONFIRM="
        + (confirmShell ? "1" : "0")
        + " && "
        + "export BROWSER=true && "
        + "export DSHA_PRELOAD_PREVIOUS=\"${NODE_OPTIONS-}\" && "
        + "export NODE_OPTIONS=\"--import=/root/.dsh/startup-observer.cjs ${NODE_OPTIONS-}\" && "
        + "export DSHA_UI_LANGUAGE="
        + ShellQuote.arg(language)
        + " && "
        + "export DSHA_STARTUP_PROFILE="
        + ShellQuote.arg(profile)
        + " && "
        + "export DSHA_WEB_GENERATION="
        + generation
        + " && cd /root && rm -f "
        + WebProcSel.IDENTITY_WEB
        + "; echo $$ > "
        + WebProcSel.PID_WEB
        + " 2>/dev/null; "
        + "[ ! -e "
        + WebProcSel.STOP_SENTINEL
        + " ] || exit 0; exec dsh "
        + ("web".equals(profile) ? "web" : "--profile " + ShellQuote.arg(profile))
        + " --no-open --host 127.0.0.1 --port "
        + listenPort;
  }
}
