package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class RuntimeStateDiagnosticsTest {
  @Test
  public void unknownStateIsSeparateFromPendingRecordsAndSuccessfulStop() {
    var state = new RuntimeStateDiagnostics.Snapshot();
    state.generation = 7;
    state.sessionUnknown = true;
    state.recoveryReason = "LAUNCHER_UNKNOWN";
    state.stopStatus = "UNCONFIRMED";
    state.stopCode = "WEB_PROCESS_EXIT_UNCONFIRMED";
    state.stopFacts = List.of(WebStopDiagnostic.Code.OWNED_WEB_SCAN_UNCONFIRMED);
    state.boundedPending = 2;
    state.environmentSharedLeases = 1;
    state.maintenance =
        List.of(new RuntimeStateDiagnostics.Maintenance("plugin-install", "PENDING"));
    String report = RuntimeStateDiagnostics.render(state);
    assertTrue(report.contains("sessionUnknown=true"));
    assertTrue(report.contains("recoveryReason=LAUNCHER_UNKNOWN"));
    assertTrue(report.contains("boundedPendingRecords=2"));
    assertTrue(report.contains("environmentExclusive=false"));
    assertTrue(report.contains("environmentSharedLeases=1"));
    assertTrue(report.contains("maintenanceExclusive=false"));
    assertTrue(report.contains("maintenance=plugin-install:PENDING"));
    assertFalse(report.contains("stopStatus=STOPPED"));
  }

  @Test
  public void untrustedValuesCannotInjectSecretsPathsOrNewFields() {
    var state = new RuntimeStateDiagnostics.Snapshot();
    String privateValue = "https://localhost/?token=private-token\n/root/user/session";
    state.recoveryReason = privateValue;
    state.stopStatus = privateValue;
    state.stopCode = privateValue;
    state.maintenance =
        List.of(new RuntimeStateDiagnostics.Maintenance(privateValue, privateValue));
    String report = RuntimeStateDiagnostics.render(state);
    assertFalse(report.contains("private-token"));
    assertFalse(report.contains("/root"));
    assertFalse(report.contains("https"));
    assertTrue(report.contains("recoveryReason=UNAVAILABLE"));
    assertTrue(report.contains("maintenance=UNAVAILABLE:UNAVAILABLE"));
  }

  @Test
  public void snapshotOutputIsBoundedWithoutCopyingTaskOrTransactionContents() {
    var state = new RuntimeStateDiagnostics.Snapshot();
    state.activeTasks = Integer.MAX_VALUE;
    state.environmentSharedLeases = Integer.MAX_VALUE;
    state.boundedPending = -1;
    state.recoveryAttempts = Integer.MAX_VALUE;
    state.maintenance =
        Collections.nCopies(
            10000, new RuntimeStateDiagnostics.Maintenance("host-data", "UNREADABLE"));
    state.stopFacts = Collections.nCopies(10000, WebStopDiagnostic.Code.OWNED_LAUNCHER_PENDING);
    String report = RuntimeStateDiagnostics.render(state);
    assertTrue(report.length() < 2200);
    assertTrue(report.contains("runtimeTasks=65535"));
    assertTrue(report.contains("environmentSharedLeases=65535"));
    assertTrue(report.contains("authChecks=3"));
    assertTrue(report.contains("boundedPendingRecords=0"));
    assertEquals(16, report.split("maintenance=", -1).length - 1);
    assertEquals(16, report.split("stopFact=", -1).length - 1);
  }
}
