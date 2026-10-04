package com.deepseekharness.app.util;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class ColdInstallPlanTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static ColdInstallPlan.Observation result(int exit, boolean exited, String text) {
    return new ColdInstallPlan.Observation(exit, false, exited, text, "exit=" + exit + "\n" + text);
  }

  private static ColdInstallPlan.Observation ready(boolean probe) {
    return result(0, true, probe ? ColdInstallPlan.PROBE_READY : ColdInstallPlan.INSTALL_READY);
  }

  @Test
  public void huaweiFailureSequenceSelectsNoSeccompAndWritesPackagesOnlyOnce() throws Exception {
    List<String> calls = new ArrayList<>(), records = new ArrayList<>();
    var selected =
        ColdInstallPlan.run(
            ColdInstallPlan.modes(true, true, true, false),
            (mode, probe) -> {
              calls.add(mode + ":" + probe);
              if (probe && mode == ColdInstallPlan.Mode.PROROOT)
                return result(126, true, "/bin/bash: Invalid argument");
              if (probe && mode == ColdInstallPlan.Mode.PROOT)
                return result(1, true, "mkdir: Function not implemented");
              return ready(probe);
            },
            (mode, probe, value) -> records.add(mode + ":" + probe + ":" + value.exitCode));
    assertEquals(ColdInstallPlan.Mode.PROOT_COMPAT, selected);
    assertEquals(
        List.of("PROROOT:true", "PROOT:true", "PROOT_COMPAT:true", "PROOT_COMPAT:false"), calls);
    assertEquals(4, records.size());
    assertTrue(records.get(0).endsWith(":126"));
  }

  @Test
  public void installedFailureNeverReplaysDpkgOnAnotherRuntime() {
    List<String> calls = new ArrayList<>();
    IOException failed =
        assertThrows(
            IOException.class,
            () ->
                ColdInstallPlan.run(
                    ColdInstallPlan.modes(true, true, true, false),
                    (mode, probe) -> {
                      calls.add(mode + ":" + probe);
                      return probe ? ready(true) : result(1, true, "dpkg: configuration failed");
                    },
                    (mode, probe, value) -> {}));
    assertTrue(failed.getMessage().startsWith("COLD_INSTALL_POSTCHECK_FAILED"));
    assertEquals(List.of("PROROOT:true", "PROROOT:false"), calls);
  }

  @Test
  public void everyFailedProbeStopsWithoutInstallOrReady() {
    List<Boolean> calls = new ArrayList<>();
    assertThrows(
        IOException.class,
        () ->
            ColdInstallPlan.run(
                ColdInstallPlan.modes(true, true, true, false),
                (mode, probe) -> {
                  calls.add(probe);
                  return result(1, true, "failed");
                },
                (mode, probe, value) -> {}));
    assertEquals(List.of(true, true, true), calls);
  }

  @Test
  public void unknownExitBlocksAllLaterProbesAndPackageWrites() {
    List<Boolean> calls = new ArrayList<>();
    assertEquals(
        "COLD_INSTALL_PROCESS_EXIT_UNCONFIRMED",
        assertThrows(
                IOException.class,
                () ->
                    ColdInstallPlan.run(
                        ColdInstallPlan.modes(true, true, true, false),
                        (mode, probe) -> {
                          calls.add(probe);
                          return result(0, false, ColdInstallPlan.PROBE_READY);
                        },
                        (mode, probe, value) -> {}))
            .getMessage());
    assertEquals(List.of(true), calls);
  }

  @Test
  public void timeoutAndFakeMarkerCannotAuthorizeInstallation() {
    assertFalse(
        new ColdInstallPlan.Observation(0, true, true, ColdInstallPlan.PROBE_READY, "")
            .ready(ColdInstallPlan.PROBE_READY));
    assertFalse(result(1, true, ColdInstallPlan.PROBE_READY).ready(ColdInstallPlan.PROBE_READY));
    assertFalse(
        result(0, true, "notice:" + ColdInstallPlan.PROBE_READY)
            .ready(ColdInstallPlan.PROBE_READY));
    assertTrue(
        result(0, true, "\n" + ColdInstallPlan.PROBE_READY + "\n")
            .ready(ColdInstallPlan.PROBE_READY));
  }

  @Test
  public void preferenceAndIsolationBoundTheProbePlanAndFlags() {
    assertEquals(
        List.of(ColdInstallPlan.Mode.PROOT, ColdInstallPlan.Mode.PROOT_COMPAT),
        ColdInstallPlan.modes(false, true, true, false));
    assertEquals(
        List.of(ColdInstallPlan.Mode.PROOT_COMPAT), ColdInstallPlan.modes(true, true, false, true));
    Map<String, String> environment = new HashMap<>();
    environment.put("PROOT_NO_SECCOMP", "old");
    ColdInstallPlan.applyEnvironment(environment, ColdInstallPlan.Mode.PROOT);
    assertFalse(environment.containsKey("PROOT_NO_SECCOMP"));
    ColdInstallPlan.applyEnvironment(environment, ColdInstallPlan.Mode.PROOT_COMPAT);
    assertEquals("1", environment.get("PROOT_NO_SECCOMP"));
    assertThrows(
        IllegalArgumentException.class, () -> ColdInstallPlan.probeCommand("/root/../user"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ColdInstallPlan.installCommand("/root/.dsha-bundled-tools; rm"));
    String command = ColdInstallPlan.probeCommand("/root/.dsha-cold-probe-" + UUID.randomUUID());
    assertTrue(command.contains("/bin/bash -c"));
    assertTrue(command.contains("/bin/mkdir"));
    assertTrue(command.contains("/usr/bin/python3"));
    assertFalse(command.contains("dpkg"));
  }

  @Test
  public void realNestedShellFileAndPythonProbeCleansOnlyItsOwnDisposableSlot() throws Exception {
    File parent = temporary.newFolder(), slot = new File(parent, "own-probe");
    var result = hostProbe(slot, false);
    assertEquals(result.output, 0, result.exitCode);
    assertTrue(result.ready(ColdInstallPlan.PROBE_READY));
    assertFalse(slot.exists());
    File existing = new File(parent, "existing");
    assertTrue(existing.mkdir());
    File original = new File(existing, "keep");
    Files.writeString(original.toPath(), "keep");
    var refused = hostProbe(existing, false);
    assertEquals(64, refused.exitCode);
    assertEquals("keep", Files.readString(original.toPath()));
  }

  @Test
  public void realProbeFailureDoesNotClaimReadinessOrLeaveItsPlaintext() throws Exception {
    File slot = new File(temporary.newFolder(), "own-probe");
    var result = hostProbe(slot, true);
    assertNotEquals(0, result.exitCode);
    assertFalse(result.ready(ColdInstallPlan.PROBE_READY));
    assertFalse(slot.exists());
  }

  @Test
  public void failedPreferenceCommitCannotPublishANewReadyMarkerOrDeleteOldEvidence()
      throws Exception {
    File directory = temporary.newFolder(),
        marker = new File(directory, "ready"),
        old = new File(directory, "old-evidence");
    Files.writeString(old.toPath(), "retain");
    assertThrows(
        IOException.class,
        () ->
            ColdInstallPlan.publishReady(
                () -> {
                  throw new IOException("preferences unavailable");
                },
                () -> Files.writeString(marker.toPath(), "ready")));
    assertFalse(marker.exists());
    assertEquals("retain", Files.readString(old.toPath()));
    List<String> order = new ArrayList<>();
    ColdInstallPlan.publishReady(
        () -> order.add("verified selection"),
        () -> {
          order.add("ready marker");
          Files.writeString(marker.toPath(), "ready");
        });
    assertEquals(List.of("verified selection", "ready marker"), order);
    assertEquals("ready", Files.readString(marker.toPath()));
  }

  private ColdInstallPlan.Observation hostProbe(File slot, boolean failPython) throws Exception {
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    String bash = windows ? "C:/Program Files/Git/bin/bash.exe" : "/bin/bash";
    org.junit.Assume.assumeTrue("host Bash is unavailable", new File(bash).isFile());
    String script = ColdInstallPlan.probeScript();
    String python = System.getenv("DSHA_PYTHON");
    if (failPython) script = script.replace("/usr/bin/python3", "/bin/false");
    else if (python != null && !python.isEmpty())
      script = script.replace("/usr/bin/python3", ShellQuote.arg(python.replace('\\', '/')));
    else if (windows) org.junit.Assume.assumeTrue("set DSHA_PYTHON for host fixture", false);
    String path = slot.getAbsolutePath().replace('\\', '/');
    if (windows) path = "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2);
    Process process =
        new ProcessBuilder(bash, "-c", script, "dsha-cold-probe", path)
            .redirectErrorStream(true)
            .start();
    boolean exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
    if (!exited) {
      process.destroyForcibly();
      process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
      fail("host probe did not exit");
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return result(process.exitValue(), true, output);
  }
}
