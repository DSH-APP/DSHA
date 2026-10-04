package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class WebLaunchCommandTest {
  @Test
  public void capturesGenerationBeforePidSentinelAndExec() {
    String result = WebLaunchCommand.build("danger-full-access", false, "zh", "web", 0, 42);
    assertTrue(result.contains("export DSHA_WEB_GENERATION=42"));
    assertTrue(result.contains("export DSHA_WORKSPACE_DOCUMENTS=/root/Documents && "));
    assertTrue(result.indexOf("DSHA_WORKSPACE_DOCUMENTS=") < result.indexOf("exec dsh"));
    assertTrue(result.endsWith("exec dsh web --no-open --host 127.0.0.1 --port 0"));
    assertTrue(result.indexOf("echo $$ >") < result.indexOf("[ ! -e"));
    assertTrue(result.indexOf("[ ! -e") < result.indexOf("exec dsh"));
    assertFalse(result.contains("API_KEY"));
    assertTrue(result.contains("${NODE_OPTIONS-}"));
  }

  @Test
  public void profileAndSettingsRemainQuoted() {
    String result = WebLaunchCommand.build("ask", true, "en", "recovery'; echo injected", 3080, 1);
    assertTrue(result.contains("export DSH_CONFIRM=1"));
    assertTrue(result.contains("--profile " + ShellQuote.arg("recovery'; echo injected")));
    assertThrows(
        IllegalArgumentException.class,
        () -> WebLaunchCommand.build("ask", true, "en", "web", -1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> WebLaunchCommand.build("ask", true, "en", "web", 65536, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> WebLaunchCommand.build("ask", true, "en", "web", 1, -1));
  }
}
