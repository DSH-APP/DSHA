package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class DiagnosticReportTest {
  private static final String GOOD =
      "DSHA_TOOL_NODE=v24.14.0\nDSHA_TOOL_NPM=11.9.0\nDSHA_TOOL_PYTHON=Python 3.12.3\n";

  @Test
  public void successfulProtocolContainsAllThreeVersions() throws Exception {
    var v = DiagnosticReport.ToolVersions.parse(GOOD);
    assertEquals("v24.14.0", v.node);
    assertEquals("11.9.0", v.npm);
    assertEquals("Python 3.12.3", v.python);
    assertEquals("Node: v24.14.0\nnpm: 11.9.0\nPython: Python 3.12.3", v.detail());
  }

  @Test
  public void localizedReportsAndLogLookalikesAreNotVersionEvidence() {
    invalid("Node: v24.14.0\nnpm: 11.9.0\nPython: Python 3.12.3\n");
    invalid("旧日志 Node: v24.14.0\nPython: Python 3.12.3\n");
    invalid("DSHA_TOOL_NODE=v24.14.0\nDSHA_TOOL_PYTHON=Python 3.12.3\n");
  }

  @Test
  public void duplicateEmptyInvalidOrOversizedProtocolCannotPass() {
    invalid(GOOD + "DSHA_TOOL_NPM=11.9.0\n");
    invalid(GOOD.replace("11.9.0", ""));
    invalid(GOOD.replace("v24.14.0", "broken"));
    invalid(GOOD + "x".repeat(1500));
  }

  @Test
  public void reportAndCardsShareImmutableTypedObservations() {
    List<DiagnosticReport.Check> source = new ArrayList<>();
    var check =
        new DiagnosticReport.Check(
            "tool-versions",
            DiagnosticReport.Health.UNVERIFIED,
            "Node · npm · Python",
            "尚未验证",
            "Node: v99.0.0\nPython: Python 9.0.0");
    source.add(check);
    var snapshot = new DiagnosticReport("诊断\n", source, "建议\n");
    source.clear();
    assertEquals(1, snapshot.checks.size());
    assertSame(check, snapshot.checks.get(0));
    assertEquals(DiagnosticReport.Health.UNVERIFIED, snapshot.checks.get(0).health);
    assertTrue(
        snapshot.render().contains(check.title + " · " + check.status + "\n" + check.detail));
    var english =
        new DiagnosticReport.Check(check.key, check.health, "Tools", "Not verified", check.detail);
    assertEquals(
        snapshot.checks.get(0).health,
        new DiagnosticReport("Diagnostics", List.of(english), "").checks.get(0).health);
  }

  @Test
  public void repairFailureIsOneObservationWithoutDestroyingCollectedChecks() {
    var tools =
        new DiagnosticReport.Check(
            "tools", DiagnosticReport.Health.PASS, "Tools", "Responded", "versions");
    var repair =
        new DiagnosticReport.Check(
            "repair", DiagnosticReport.Health.NEEDS_ATTENTION, "Repair", "Failed", "failure");
    var result = new DiagnosticReport("Diagnostics", List.of(tools), "").prepend(repair);
    assertEquals(List.of(repair, tools), result.checks);
    assertTrue(result.render().contains("Repair · Failed"));
    assertTrue(result.render().contains("Tools · Responded"));
  }

  private static void invalid(String value) {
    try {
      DiagnosticReport.ToolVersions.parse(value);
      fail("invalid evidence accepted");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().startsWith("RUNTIME_TOOL_VERSIONS_"));
    }
  }
}
