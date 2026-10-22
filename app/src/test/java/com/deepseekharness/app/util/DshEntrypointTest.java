package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class DshEntrypointTest {
  @Test
  public void webAndSafeProfileDoNotDependOnGlobalOrPathSelectedDsh() {
    for (String profile : new String[] {"web", "dsha-recovery-example"}) {
      String command = WebLaunchCommand.build("ask", true, "zh", profile, 0, 8);
      assertTrue(command.contains(DshEntrypoint.exec() + " "));
      assertFalse(command.contains("exec dsh "));
      assertFalse(command.contains("/usr/local/bin/dsh "));
    }
    assertTrue(DshEntrypoint.wrapper().endsWith(" \"$@\"\n"));
    assertEquals(Constants.DSH_BIN_JS, DshEntrypoint.SCRIPT);
  }

  @Test
  public void unownedCoreFailureRemainsVisibleWithoutBlamingAPlugin() {
    StartupTrace trace = new StartupTrace();
    trace.begin(8, 0, false);
    String reason = "ERR_MODULE_NOT_FOUND: Cannot find package '@deepseek-ai/dsh-app-boot'";
    trace.issue(8, 5, "", reason);
    assertTrue(trace.snapshot(6, "zh").log.contains("DSH 启动异常"));
    assertFalse(trace.snapshot(6, "zh").log.contains("插件加载异常"));
    assertTrue(trace.snapshot(6, "en").log.contains("DSH startup error"));
    assertEquals(reason, trace.snapshot(6).issues.get(""));
    trace.issue(8, 7, "third-party-plugin", "module missing");
    assertTrue(trace.snapshot(8, "zh").log.contains("插件加载异常：third-party-plugin"));
  }
}
