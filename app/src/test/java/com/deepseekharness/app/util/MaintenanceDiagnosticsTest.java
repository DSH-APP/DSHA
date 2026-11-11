package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/** 维护失败归类与停滞判定：未知/自由文本不升级为结论，逻辑根名不接收任意输入。 */
public final class MaintenanceDiagnosticsTest {
  @Test
  public void classifyKnownCodes() {
    assertEquals(
        MaintenanceDiagnostics.Failure.NO_SPACE, MaintenanceDiagnostics.classify("NO_SPACE"));
    assertEquals(
        MaintenanceDiagnostics.Failure.ROOT_UNREADABLE,
        MaintenanceDiagnostics.classify("MAINTENANCE_SOURCE_MISSING"));
    assertEquals(
        MaintenanceDiagnostics.Failure.ROOT_UNREADABLE,
        MaintenanceDiagnostics.classify("PERMISSION_DENIED"));
    assertEquals(
        MaintenanceDiagnostics.Failure.SOURCE_CHANGED,
        MaintenanceDiagnostics.classify("DATA_LOCATION_CHANGED"));
    assertEquals(
        MaintenanceDiagnostics.Failure.SERVICE_UNAVAILABLE,
        MaintenanceDiagnostics.classify("FOREGROUND_SERVICE_UNAVAILABLE"));
    assertEquals(
        MaintenanceDiagnostics.Failure.ENTRY_LIMIT, MaintenanceDiagnostics.classify("ENTRY_LIMIT"));
    assertEquals(MaintenanceDiagnostics.Failure.OTHER, MaintenanceDiagnostics.classify(null));
    assertEquals(MaintenanceDiagnostics.Failure.OTHER, MaintenanceDiagnostics.classify("README"));
  }

  @Test
  public void stallRequiresTheFullQuietWindow() {
    assertFalse(MaintenanceDiagnostics.stalled(0));
    assertFalse(MaintenanceDiagnostics.stalled(MaintenanceDiagnostics.STALL_MS - 1));
    assertTrue(MaintenanceDiagnostics.stalled(MaintenanceDiagnostics.STALL_MS));
  }

  @Test
  public void targetOnlyAcceptsLogicalPaths() {
    assertEquals("root/.dsh", MaintenanceDiagnostics.target(" root/.dsh "));
    assertEquals(
        "usr/local/lib/node_modules/x",
        MaintenanceDiagnostics.target("usr/local/lib/node_modules/x"));
    assertEquals(
        "", MaintenanceDiagnostics.target("/data/user/0/io.github.sunsetrne.dsha/files/root"));
    assertEquals("", MaintenanceDiagnostics.target("root\n.dsh"));
    assertEquals("", MaintenanceDiagnostics.target("root/.dsh token=secret"));
    assertEquals("", MaintenanceDiagnostics.target(null));
    assertEquals("", MaintenanceDiagnostics.target("a".repeat(121)));
  }
}
