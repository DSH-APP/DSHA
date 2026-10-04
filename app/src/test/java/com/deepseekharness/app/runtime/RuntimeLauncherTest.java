package com.deepseekharness.app.runtime;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.*;
import static org.junit.Assert.*;

public class RuntimeLauncherTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private final RuntimeHostPorts ports = new RuntimeHostPorts();

  @Before
  public void hostSettings() {
    ports.install(
        new RuntimeHostPorts.Provider() {
          public RuntimeHostPorts.Settings snapshot() {
            return new RuntimeHostPorts.Settings("auto", false, true, false);
          }

          public void stage(String value) {}

          public void record(String kind, String detail) {}

          public void failure(Throwable error) {}
        });
  }

  static ContainerRuntime fake() {
    return new ContainerRuntime() {
      public String id() {
        return "fixture";
      }

      public String displayName() {
        return "fixture";
      }

      public boolean available() {
        return true;
      }

      public String unavailableReason() {
        return "";
      }

      public List<String> baseArgv(File root, boolean hardlinks) {
        throw new AssertionError("explicit domain is required");
      }

      public List<String> baseArgv(File root, boolean hardlinks, File domain) {
        return new ArrayList<>(List.of("fixture-runtime", root.getPath()));
      }

      public void applyEnv(ProcessBuilder builder, File base, File lib, File tmp) {
        builder.environment().put("FIXTURE_RUNTIME", "1");
      }

      public void prepare() {}
    };
  }

  @Test
  public void secretsStayInEnvironmentAndSpecDiagnosticsContainOnlyKeys() throws Exception {
    File root = temporary.newFolder();
    var launcher = new RuntimeLauncher(null, root, root, root, root, ports);
    Map<String, String> secrets = new HashMap<>();
    secrets.put("DEEPSEEK_API_KEY", "private-launch-key");
    LaunchSpec spec = LaunchSpec.runtime(fake()).pipedInput(true).environment(secrets).build();
    secrets.put("DEEPSEEK_API_KEY", "modified");
    var builder = launcher.builder("exec dsh web", spec);
    assertEquals("private-launch-key", builder.environment().get("DEEPSEEK_API_KEY"));
    assertFalse(builder.command().toString().contains("private-launch-key"));
    assertFalse(spec.toString().contains("private-launch-key"));
    assertTrue(spec.toString().contains("DEEPSEEK_API_KEY"));
    assertEquals("exec dsh web", builder.command().get(builder.command().size() - 1));
  }

  @Test
  public void launchSpecPreservesNamedChoicesAndRejectsMalformedEnvironment() {
    var spec =
        LaunchSpec.runtime(fake())
            .isolated(true)
            .coldTrace(true)
            .pipedInput(true)
            .bind("/private/payload", "/root/trial")
            .build();
    assertTrue(spec.isolated);
    assertTrue(spec.coldTrace);
    assertTrue(spec.pipedInput);
    assertEquals(1, spec.binds.size());
    assertThrows(UnsupportedOperationException.class, () -> spec.binds.clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> LaunchSpec.runtime(fake()).environment(Map.of("KEY", "bad\0value")).build());
  }
}
