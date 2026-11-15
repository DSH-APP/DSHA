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
    RuntimeHostPorts ports = ports();
    File root = temporary.newFolder();
    RuntimeLauncher launcher = launcher(root, ports);
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

  @Test
  public void fixedGuestCommandOnlyReplacesTheDefaultCommandTail() throws Exception {
    RuntimeHostPorts ports = ports();
    File root = temporary.newFolder();
    RuntimeLauncher launcher = launcher(root, ports);
    var spec = LaunchSpec.runtime(RuntimeLauncherTest.fake()).hardlinks(true).build();
    var defaultLaunch = TerminalRuntime.prepare(launcher, root, spec);
    String[] guest = {"/bin/bash", "-c", "exec /bin/bash -l"};
    var guestLaunch = TerminalRuntime.prepare(launcher, root, spec, guest);
    int prefix = guestLaunch.argv().length - guest.length;
    assertEquals(defaultLaunch.argv().length - 3, prefix);
    assertArrayEquals(
        Arrays.copyOf(defaultLaunch.argv(), prefix), Arrays.copyOf(guestLaunch.argv(), prefix));
    assertArrayEquals(
        guest, Arrays.copyOfRange(guestLaunch.argv(), prefix, guestLaunch.argv().length));
    assertArrayEquals(defaultLaunch.environment(), guestLaunch.environment());
    // 默认路径仍是原有的 stty 前导 + 登录 shell；固定 command 只替换最后一段。
    assertEquals(
        "stty sane 2>/dev/null || stty echo icanon 2>/dev/null || true; exec /bin/bash -l",
        defaultLaunch.argv()[defaultLaunch.argv().length - 1]);
  }

  private static RuntimeHostPorts ports() {
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
    return ports;
  }

  private static RuntimeLauncher launcher(File root, RuntimeHostPorts ports) {
    return new RuntimeLauncher(null, root, root, root, root, ports);
  }
}
