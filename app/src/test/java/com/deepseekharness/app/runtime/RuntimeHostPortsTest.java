package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import com.deepseekharness.app.util.ColdInstallPlan.Mode;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class RuntimeHostPortsTest {
  @Test
  public void coldPreparationIsProvisionalUntilRendererAndGuestExitProof() throws Exception {
    ColdHost host = new ColdHost(false);
    assertThrows(
        IOException.class,
        () -> host.ports.prepareColdRuntime(host.root, Mode.PROROOT, host.sha, host.runtimeId));
    try (var invocation = host.ports.open()) {
      host.ports.prepareColdRuntime(host.root, Mode.PROROOT, host.sha, host.runtimeId);
      assertTrue(host.ports.settings().proroot);
      assertFalse(host.current.get().proroot);
      assertTrue(host.preferences.isEmpty());
      for (String incomplete : java.util.List.of("renderer", "processExited")) {
        var proof = host.proof("proroot");
        proof.put(incomplete, false);
        assertThrows(IOException.class, () -> host.ports.confirmColdRuntime(host.root, proof));
        assertEquals(0, host.commits);
      }
      var otherRuntime = host.proof("proroot");
      otherRuntime.put("runtimeId", "2".repeat(64));
      assertThrows(IOException.class, () -> host.ports.confirmColdRuntime(host.root, otherRuntime));
      host.ports.confirmColdRuntime(host.root, host.proof("proroot"));
      assertEquals(1, host.commits);
      assertEquals(Mode.PROROOT, host.committedMode);
      assertTrue(host.current.get().proroot);
      assertThrows(
          IOException.class, () -> host.ports.confirmColdRuntime(host.root, host.proof("proroot")));
    }
    assertTrue(host.ports.settings().proroot);
  }

  @Test
  public void coldProrootFallbackNeedsTheClosedActualTrialAndKeepsSeccompIntent() throws Exception {
    ColdHost host = new ColdHost(true);
    try (var invocation = host.ports.open()) {
      host.ports.prepareColdRuntime(host.root, Mode.PROROOT, host.sha, host.runtimeId);
      var proof = host.proof("proot");
      assertThrows(IOException.class, () -> host.ports.confirmColdRuntime(host.root, proof));
      proof.put("fallbackFrom", "proroot");
      proof.put("fallbackExitCode", "127");
      assertThrows(IOException.class, () -> host.ports.confirmColdRuntime(host.root, proof));
      proof.put("fallbackExitCode", 127L);
      proof.put("processExited", false);
      assertThrows(IOException.class, () -> host.ports.confirmColdRuntime(host.root, proof));
      assertEquals(0, host.commits);
      proof.put("processExited", true);
      host.ports.confirmColdRuntime(host.root, proof);
      assertEquals(Mode.PROOT_COMPAT, host.committedMode);
      assertFalse(host.ports.settings().proroot);
      assertTrue(host.ports.settings().disableProotSeccomp);
    }
    assertFalse(host.current.get().proroot);
    assertTrue(host.current.get().disableProotSeccomp);
  }

  @Test
  public void dynamicLoaderRemainsProvisionalAndOnlyAClosedFullTrialCommitsTheActualFlag()
      throws Exception {
    ColdHost host = new ColdHost(false);
    host.preferences.put("proroot_static_loader", true);
    try (var invocation = host.ports.open()) {
      host.ports.prepareColdRuntime(host.root, Mode.PROROOT_DYNAMIC, host.sha, host.runtimeId);
      assertTrue(host.ports.settings().proroot);
      assertFalse(host.ports.settings().staticLoader);
      assertTrue(host.current.get().staticLoader);
      assertEquals(true, host.preferences.get("proroot_static_loader"));
      var proof = host.proof("proroot");
      proof.put("processExited", false);
      assertThrows(IOException.class, () -> host.ports.confirmColdRuntime(host.root, proof));
      assertEquals(0, host.commits);
      proof.put("processExited", true);
      host.ports.confirmColdRuntime(host.root, proof);
      assertEquals(Mode.PROROOT_DYNAMIC, host.committedMode);
      assertTrue(host.current.get().proroot);
      assertFalse(host.current.get().staticLoader);
      assertEquals(false, host.preferences.get("proroot_static_loader"));
    }
  }

  @Test
  public void explicitChoiceOrChangedStaticFlagCannotConfirmDynamicAndClosedProotKeepsOldFlag()
      throws Exception {
    ColdHost explicit = new ColdHost(false);
    explicit.automatic = false;
    try (var invocation = explicit.ports.open()) {
      assertThrows(
          IOException.class,
          () ->
              explicit.ports.prepareColdRuntime(
                  explicit.root, Mode.PROROOT_DYNAMIC, explicit.sha, explicit.runtimeId));
    }
    ColdHost host = new ColdHost(true);
    host.preferences.put("proroot_static_loader", true);
    try (var invocation = host.ports.open()) {
      host.ports.prepareColdRuntime(host.root, Mode.PROROOT_DYNAMIC, host.sha, host.runtimeId);
      host.preferences.put("proroot_static_loader", false);
      assertThrows(
          IOException.class, () -> host.ports.confirmColdRuntime(host.root, host.proof("proroot")));
      host.preferences.put("proroot_static_loader", true);
      var proof = host.proof("proot");
      proof.put("fallbackFrom", "proroot");
      proof.put("fallbackExitCode", 139L);
      host.ports.confirmColdRuntime(host.root, proof);
      assertEquals(Mode.PROOT_COMPAT, host.committedMode);
      assertTrue(host.current.get().staticLoader);
      assertEquals(true, host.preferences.get("proroot_static_loader"));
    }
  }

  @Test
  public void changedRootOrUserSelectionCannotBeConfirmedAndScopeDropsPendingChoice()
      throws Exception {
    ColdHost host = new ColdHost(false);
    try (var invocation = host.ports.open()) {
      host.ports.prepareColdRuntime(host.root, Mode.PROROOT, host.sha, host.runtimeId);
      host.identity = "replacement-root";
      assertThrows(
          IOException.class, () -> host.ports.confirmColdRuntime(host.root, host.proof("proroot")));
      host.identity = "published-root";
      host.preferences.put("container_runtime", "proot");
      assertThrows(
          IOException.class, () -> host.ports.confirmColdRuntime(host.root, host.proof("proroot")));
      host.preferences.clear();
      host.current.set(new RuntimeHostPorts.Settings("ipv4", false, false, false));
      assertThrows(
          IOException.class, () -> host.ports.confirmColdRuntime(host.root, host.proof("proroot")));
      assertEquals(0, host.commits);
    }
    assertFalse(host.ports.settings().proroot);
    try (var next = host.ports.open()) {
      assertThrows(
          IOException.class, () -> host.ports.confirmColdRuntime(host.root, host.proof("proroot")));
    }
  }

  @Test
  public void explicitProotCannotPrepareProrootAndProotCandidateKeepsItsVerifiedFlags()
      throws Exception {
    ColdHost host = new ColdHost(false);
    host.automatic = false;
    try (var invocation = host.ports.open()) {
      assertThrows(
          IOException.class,
          () -> host.ports.prepareColdRuntime(host.root, Mode.PROROOT, host.sha, host.runtimeId));
      assertFalse(host.ports.settings().proroot);
      host.ports.prepareColdRuntime(host.root, Mode.PROOT_COMPAT, host.sha, host.runtimeId);
      host.ports.confirmColdRuntime(host.root, host.proof("proot"));
      assertEquals(Mode.PROOT_COMPAT, host.committedMode);
      assertTrue(host.current.get().disableProotSeccomp);
      assertEquals(1, host.commits);
    }
  }

  private static final class ColdHost {
    final RuntimeHostPorts ports = new RuntimeHostPorts();
    final java.io.File root = new java.io.File("published-root");
    final String sha = "0".repeat(64), runtimeId = "1".repeat(64);
    final java.util.Map<String, Object> preferences = new java.util.HashMap<>();
    final AtomicReference<RuntimeHostPorts.Settings> current;
    String identity = "published-root";
    boolean automatic = true;
    int commits;
    Mode committedMode;

    ColdHost(boolean noSeccomp) {
      current =
          new AtomicReference<>(new RuntimeHostPorts.Settings("ipv4", false, true, noSeccomp));
      ports.install(
          new RuntimeHostPorts.Provider() {
            public RuntimeHostPorts.Settings snapshot() {
              return current.get();
            }

            public boolean preferFastColdMode() {
              return automatic;
            }

            public void stage(String value) {}

            public void record(String kind, String detail) {}

            public void failure(Throwable error) {}

            public java.util.Map<String, Object> snapshotColdSelection() {
              return java.util.Map.copyOf(preferences);
            }

            public String coldRuntimeIdentity(java.io.File checked) throws IOException {
              if (!root.getCanonicalFile().equals(checked.getCanonicalFile()))
                throw new IOException("fixture-root-changed");
              return identity;
            }

            public RuntimeHostPorts.Settings successfulColdRuntime(
                java.io.File checked, Mode mode, String packageSha) {
              commits++;
              committedMode = mode;
              preferences.put("container_runtime", mode.runtime);
              preferences.put("cold_runtime_root", identity);
              preferences.put("cold_runtime_packages", packageSha);
              preferences.put("cold_runtime_mode", mode.name());
              if (mode == Mode.PROROOT_DYNAMIC) preferences.put("proroot_static_loader", false);
              var stored = current.get();
              var selected =
                  new RuntimeHostPorts.Settings(
                      stored.dnsMode,
                      "proroot".equals(mode.runtime),
                      mode.staticLoader(stored.staticLoader),
                      "proroot".equals(mode.runtime) ? stored.disableProotSeccomp : mode.noSeccomp);
              current.set(selected);
              return selected;
            }
          });
    }

    java.util.Map<String, Object> proof(String actualMode) {
      java.util.Map<String, Object> proof = new java.util.HashMap<>();
      proof.put("runtimeId", runtimeId);
      proof.put("nonce", "a".repeat(32));
      proof.put("port", 3080L);
      proof.put("confirmedAt", 1L);
      proof.put("runtimeMode", actualMode);
      for (String check :
          java.util.List.of(
              "assets",
              "nativeModules",
              "process",
              "authentication",
              "localApi",
              "renderer",
              "dataRead",
              "dataWrite",
              "storageFreshReopened",
              "processExited")) proof.put(check, true);
      return proof;
    }
  }

  @Test
  public void fastColdPermissionIsExplicitAndDoesNotSelectTheInvocationRuntime() {
    RuntimeHostPorts ordinary = new RuntimeHostPorts(), fresh = new RuntimeHostPorts();
    ordinary.install(
        provider(new AtomicReference<>(new RuntimeHostPorts.Settings("auto", false, true, false))));
    fresh.install(
        new RuntimeHostPorts.Provider() {
          public RuntimeHostPorts.Settings snapshot() {
            return new RuntimeHostPorts.Settings("auto", false, true, false);
          }

          public boolean preferFastColdMode() {
            return true;
          }

          public void stage(String value) {}

          public void record(String kind, String detail) {}

          public void failure(Throwable error) {}
        });
    assertFalse(ordinary.preferFastColdMode());
    try (var invocation = fresh.open()) {
      assertTrue(fresh.preferFastColdMode());
      assertFalse(fresh.settings().proroot);
      assertTrue(fresh.settings().staticLoader);
    }
    assertFalse(fresh.settings().proroot);
  }

  @Test
  public void applicationOwnersHaveIndependentSnapshotsAndDiagnosticHistories() {
    RuntimeHostPorts first = new RuntimeHostPorts(), second = new RuntimeHostPorts();
    first.install(
        provider(new AtomicReference<>(new RuntimeHostPorts.Settings("auto", false, true, false))));
    second.install(
        provider(new AtomicReference<>(new RuntimeHostPorts.Settings("ipv4", true, false, true))));
    RuntimeHostPorts.Owner firstOwner = () -> first, secondOwner = () -> second;
    assertEquals(first, RuntimeHostPorts.fromOwner(firstOwner));
    assertEquals(second, RuntimeHostPorts.fromOwner(secondOwner));
    try (var invocation = first.open()) {
      assertEquals("auto", first.settings().dnsMode);
      assertEquals("ipv4", second.settings().dnsMode);
    }
    assertTrue(first.diagnosticFailures().isEmpty());
    assertTrue(second.diagnosticFailures().isEmpty());
    assertThrows(IllegalStateException.class, () -> RuntimeHostPorts.fromOwner(new Object()));
    assertThrows(
        IllegalStateException.class,
        () -> RuntimeHostPorts.fromOwner((RuntimeHostPorts.Owner) () -> null));
  }

  @Test
  public void sinkFailuresKeepTheirFirstCauseAndOrderedRedactedSnapshots() {
    RuntimeHostPorts ports = new RuntimeHostPorts();
    ports.install(
        new RuntimeHostPorts.Provider() {
          public RuntimeHostPorts.Settings snapshot() {
            return new RuntimeHostPorts.Settings("auto", false, true, false);
          }

          public void stage(String s) {
            throw new IllegalArgumentException("password=private-value\nsecond line");
          }

          public void record(String k, String d) {
            throw new IllegalStateException("token=private-value");
          }

          public void failure(Throwable e) {
            throw new UnsupportedOperationException("sink failed");
          }
        });
    ports.stage("extract");
    ports.record("RUN", "ignored");
    var entries = ports.diagnosticFailures();
    assertEquals(2, entries.size());
    assertEquals("stage", entries.get(0).operation());
    assertEquals("IllegalArgumentException", entries.get(0).type());
    assertEquals("record:RUN", entries.get(1).operation());
    assertFalse(ports.diagnosticFailure().contains("private-value"));
    assertFalse(ports.diagnosticFailure().contains("second line"));
    for (int i = 0; i < 12; i++) ports.failure(new IOException("operation"));
    assertEquals(8, ports.diagnosticFailures().size());
    assertTrue(ports.diagnosticFailure().contains("first: "));
    assertTrue(ports.diagnosticFailure().contains("stage IllegalArgumentException"));
    assertThrows(UnsupportedOperationException.class, () -> entries.clear());
  }

  @Test
  public void restoringColdSelectionUpdatesExistingInvocationAndDoesNotReplayOtherSettings()
      throws Exception {
    RuntimeHostPorts ports = new RuntimeHostPorts();
    var state = new AtomicReference<>(new RuntimeHostPorts.Settings("ipv4", true, true, false));
    var prefs = new java.util.HashMap<String, Object>();
    prefs.put("container_runtime", "proroot");
    ports.install(
        new RuntimeHostPorts.Provider() {
          public RuntimeHostPorts.Settings snapshot() {
            return state.get();
          }

          public void stage(String s) {}

          public void record(String k, String d) {}

          public void failure(Throwable e) {}

          public java.util.Map<String, Object> snapshotColdSelection() {
            return java.util.Map.copyOf(prefs);
          }

          public void restoreColdSelection(java.util.Map<String, Object> before, String root) {
            org.junit.Assert.assertEquals(root, prefs.get("cold_runtime_root"));
            prefs.clear();
            prefs.putAll(before);
            state.set(new RuntimeHostPorts.Settings("ipv4", true, true, false));
          }

          public RuntimeHostPorts.Settings successfulColdRuntime(
              java.io.File root,
              com.deepseekharness.app.util.ColdInstallPlan.Mode mode,
              String sha) {
            prefs.put("container_runtime", "proot");
            prefs.put("cold_runtime_root", "new-root");
            var next = new RuntimeHostPorts.Settings("ipv4", false, true, true);
            state.set(next);
            return next;
          }
        });
    try (var invocation = ports.open()) {
      var before = ports.snapshotColdSelection();
      ports.successfulColdRuntime(
          new java.io.File("root"),
          com.deepseekharness.app.util.ColdInstallPlan.Mode.PROOT_COMPAT,
          "0".repeat(64));
      assertFalse(ports.settings().proroot);
      ports.restoreColdSelection(before, "new-root");
      assertTrue(ports.settings().proroot);
      assertFalse(ports.settings().disableProotSeccomp);
      assertEquals("ipv4", ports.settings().dnsMode);
    }
    assertTrue(ports.settings().proroot);
  }

  @Test
  public void uninitializedProviderFailsClosed() {
    RuntimeHostPorts ports = new RuntimeHostPorts();
    assertThrows(IllegalStateException.class, ports::settings);
    assertThrows(IllegalStateException.class, ports::open);
    assertThrows(IllegalStateException.class, () -> ports.stage("extract"));
  }

  @Test
  public void oneInvocationKeepsModeDnsAndFlagsTogetherThenNextSeesSwitch() {
    RuntimeHostPorts ports = new RuntimeHostPorts();
    AtomicReference<RuntimeHostPorts.Settings> current =
        new AtomicReference<>(new RuntimeHostPorts.Settings("auto", false, true, false));
    RuntimeHostPorts.Provider provider = provider(current);
    ports.install(provider);
    try (RuntimeHostPorts.Scope outer = ports.open()) {
      assertEquals("auto", ports.settings().dnsMode);
      assertFalse(ports.settings().proroot);
      assertTrue(ports.settings().staticLoader);
      current.set(new RuntimeHostPorts.Settings("ipv4", true, false, true));
      try (RuntimeHostPorts.Scope inner = ports.open()) {
        assertEquals("auto", ports.settings().dnsMode);
        assertFalse(ports.settings().disableProotSeccomp);
      }
      assertFalse(ports.settings().proroot);
    }
    try (RuntimeHostPorts.Scope next = ports.open()) {
      assertEquals("ipv4", ports.settings().dnsMode);
      assertTrue(ports.settings().proroot);
      assertFalse(ports.settings().staticLoader);
      assertTrue(ports.settings().disableProotSeccomp);
    }
    assertThrows(IllegalStateException.class, () -> ports.install(provider(current)));
  }

  @Test
  public void cancellationOrExceptionReleasesSnapshotAndDiagnosticFailureDoesNotMaskIt() {
    RuntimeHostPorts ports = new RuntimeHostPorts();
    AtomicReference<RuntimeHostPorts.Settings> current =
        new AtomicReference<>(new RuntimeHostPorts.Settings("native", false, true, false));
    ports.install(
        new RuntimeHostPorts.Provider() {
          @Override
          public RuntimeHostPorts.Settings snapshot() {
            return current.get();
          }

          @Override
          public void stage(String value) {
            throw new IllegalStateException("sink unavailable");
          }

          @Override
          public void record(String kind, String detail) {
            throw new IllegalStateException("sink unavailable");
          }

          @Override
          public void failure(Throwable error) {
            throw new IllegalStateException("sink unavailable");
          }
        });
    assertThrows(
        InterruptedException.class,
        () -> {
          try (RuntimeHostPorts.Scope ignored = ports.open()) {
            assertEquals("native", ports.settings().dnsMode);
            ports.failure(new InterruptedException("cancelled"));
            throw new InterruptedException("cancelled");
          }
        });
    assertTrue(ports.diagnosticFailure().contains("failure IllegalStateException"));
    current.set(new RuntimeHostPorts.Settings("auto", false, true, false));
    assertEquals("auto", ports.settings().dnsMode);
  }

  private static RuntimeHostPorts.Provider provider(
      AtomicReference<RuntimeHostPorts.Settings> current) {
    return new RuntimeHostPorts.Provider() {
      @Override
      public RuntimeHostPorts.Settings snapshot() {
        return current.get();
      }

      @Override
      public void stage(String value) {}

      @Override
      public void record(String kind, String detail) {}

      @Override
      public void failure(Throwable error) {}
    };
  }

  @Test
  public void verifiedSelectionUpdatesTheOpenInvocationAndTheNextInvocationTogether()
      throws Exception {
    RuntimeHostPorts ports = new RuntimeHostPorts();
    var current = new AtomicReference<>(new RuntimeHostPorts.Settings("ipv4", true, true, false));
    ports.install(
        new RuntimeHostPorts.Provider() {
          @Override
          public RuntimeHostPorts.Settings snapshot() {
            return current.get();
          }

          @Override
          public void stage(String value) {}

          @Override
          public void record(String kind, String detail) {}

          @Override
          public void failure(Throwable error) {}

          @Override
          public RuntimeHostPorts.Settings successfulColdRuntime(
              java.io.File root,
              com.deepseekharness.app.util.ColdInstallPlan.Mode mode,
              String sha) {
            var value =
                new RuntimeHostPorts.Settings(
                    current.get().dnsMode, false, current.get().staticLoader, true);
            current.set(value);
            return value;
          }
        });
    try (var scope = ports.open()) {
      assertTrue(ports.settings().proroot);
      assertFalse(ports.settings().disableProotSeccomp);
      ports.successfulColdRuntime(
          new java.io.File("prepared"),
          com.deepseekharness.app.util.ColdInstallPlan.Mode.PROOT_COMPAT,
          "0".repeat(64));
      assertFalse(ports.settings().proroot);
      assertTrue(ports.settings().disableProotSeccomp);
      assertEquals("ipv4", ports.settings().dnsMode);
      try (var nested = ports.open()) {
        assertTrue(ports.settings().disableProotSeccomp);
      }
    }
    assertFalse(ports.settings().proroot);
    assertTrue(ports.settings().disableProotSeccomp);
  }

  @Test
  public void missingSelectionAuthorityCannotChangeAnOpenRuntimeSnapshot() throws Exception {
    RuntimeHostPorts ports = new RuntimeHostPorts();
    var current = new AtomicReference<>(new RuntimeHostPorts.Settings("auto", true, true, false));
    ports.install(provider(current));
    try (var scope = ports.open()) {
      assertThrows(
          java.io.IOException.class,
          () ->
              ports.successfulColdRuntime(
                  new java.io.File("prepared"),
                  com.deepseekharness.app.util.ColdInstallPlan.Mode.PROOT_COMPAT,
                  "0".repeat(64)));
      assertTrue(ports.settings().proroot);
      assertFalse(ports.settings().disableProotSeccomp);
    }
    assertTrue(current.get().proroot);
    assertFalse(current.get().disableProotSeccomp);
  }

  @Test
  public void loaderPreferenceChangedAfterProofCannotPublishProrootAsProven() throws Exception {
    RuntimeHostPorts ports = new RuntimeHostPorts();
    ports.install(
        new RuntimeHostPorts.Provider() {
          public RuntimeHostPorts.Settings snapshot() {
            return new RuntimeHostPorts.Settings("auto", true, true, false);
          }

          public void stage(String s) {}

          public void record(String k, String d) {}

          public void failure(Throwable e) {}

          public RuntimeHostPorts.Settings successfulColdRuntime(
              java.io.File r, com.deepseekharness.app.util.ColdInstallPlan.Mode m, String s) {
            return new RuntimeHostPorts.Settings("auto", true, false, false);
          }
        });
    try (var scope = ports.open()) {
      assertThrows(
          java.io.IOException.class,
          () ->
              ports.successfulColdRuntime(
                  new java.io.File("root"),
                  com.deepseekharness.app.util.ColdInstallPlan.Mode.PROROOT,
                  "0".repeat(64)));
      assertTrue(ports.settings().staticLoader);
    }
  }
}
