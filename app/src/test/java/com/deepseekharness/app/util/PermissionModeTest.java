package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PermissionModeTest {
  @Test
  public void formalWebUsesFullAccessWithoutTakingTheRetiredNativeChoice() {
    for (String profile : new String[] {"web", "dsha-startup-safe"}) {
      String command = WebLaunchCommand.build(true, "zh", profile, 0, 1);
      assertTrue(command.contains("export DSH_PERMISSION_MODE='danger-full-access' && "));
      assertFalse(command.contains("workspace-write"));
      assertFalse(command.contains("read-only"));
    }
  }
}
