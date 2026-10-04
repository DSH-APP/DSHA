package com.deepseekharness.app.runtime;

import java.io.File;
import java.util.Arrays;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TerminalRuntimeTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void argvAndEnvironmentUseOneExplicitRuntimeAndSettingsImage() throws Exception {
    RuntimeHostPorts ports = new RuntimeHostPorts();
    ports.install(
        new RuntimeHostPorts.Provider() {
          public RuntimeHostPorts.Settings snapshot() {
            return new RuntimeHostPorts.Settings("ipv4", false, false, false);
          }

          public void stage(String value) {}

          public void record(String a, String b) {}

          public void failure(Throwable t) {}
        });
    File root = temporary.newFolder();
    RuntimeLauncher launcher = new RuntimeLauncher(null, root, root, root, root, ports);
    var spec = LaunchSpec.runtime(RuntimeLauncherTest.fake()).hardlinks(true).build();
    var result = TerminalRuntime.prepare(launcher, root, spec, "/bin/bash", "-l");
    assertEquals("fixture-runtime", result.argv()[0]);
    assertArrayEquals(
        new String[] {"fixture-runtime", root.getPath(), "/bin/bash", "-l"}, result.argv());
    assertTrue(Arrays.asList(result.environment()).contains("FIXTURE_RUNTIME=1"));
    assertTrue(Arrays.asList(result.environment()).contains("DSHA_DNS_MODE=ipv4"));
    assertTrue(Arrays.asList(result.environment()).contains("LANG=C.UTF-8"));
    assertTrue(Arrays.asList(result.environment()).contains("DSHA_PLUGIN_UNLOCKED=1"));
  }
}
