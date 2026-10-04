package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class RuntimeHostPortsTest {
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
