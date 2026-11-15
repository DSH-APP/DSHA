package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.DiagnosticReport.Health;
import com.deepseekharness.app.util.DiagnosticSummary.Group;
import com.deepseekharness.app.util.DiagnosticSummary.Tone;
import java.util.List;
import org.junit.Test;

public final class DiagnosticSummaryTest {
  @Test
  public void groupFollowsHealthEnumOnly() {
    assertEquals(Group.ATTENTION, DiagnosticSummary.group(Health.NEEDS_ATTENTION));
    assertEquals(Group.UNVERIFIED, DiagnosticSummary.group(Health.UNVERIFIED));
    assertEquals(Group.OK, DiagnosticSummary.group(Health.PASS));
    assertEquals(Group.OK, DiagnosticSummary.group(Health.INFO));
  }

  @Test
  public void orderPutsProblemsFirstAndKeepsProbeOrderWithinGroup() {
    List<Health> healths =
        List.of(
            Health.PASS,
            Health.UNVERIFIED,
            Health.INFO,
            Health.NEEDS_ATTENTION,
            Health.PASS,
            Health.NEEDS_ATTENTION);
    assertEquals(List.of(3, 5, 1, 0, 2, 4), DiagnosticSummary.order(healths));
    assertEquals(List.of(), DiagnosticSummary.order(List.of()));
  }

  @Test
  public void countsPerGroup() {
    List<Health> healths =
        List.of(Health.PASS, Health.UNVERIFIED, Health.NEEDS_ATTENTION, Health.INFO);
    assertEquals(1, DiagnosticSummary.count(healths, Group.ATTENTION));
    assertEquals(1, DiagnosticSummary.count(healths, Group.UNVERIFIED));
    assertEquals(2, DiagnosticSummary.count(healths, Group.OK));
  }

  @Test
  public void toneUsesMostSevereStateWithRunningAndFailureFirst() {
    List<Health> mixed = List.of(Health.PASS, Health.UNVERIFIED, Health.NEEDS_ATTENTION);
    assertEquals(Tone.RUNNING, DiagnosticSummary.tone(true, true, mixed));
    assertEquals(Tone.FAILED, DiagnosticSummary.tone(false, true, mixed));
    assertEquals(Tone.FAILED, DiagnosticSummary.tone(false, true, List.of()));
    assertEquals(Tone.IDLE, DiagnosticSummary.tone(false, false, List.of()));
    assertEquals(Tone.ATTENTION, DiagnosticSummary.tone(false, false, mixed));
    assertEquals(
        Tone.UNVERIFIED,
        DiagnosticSummary.tone(false, false, List.of(Health.PASS, Health.UNVERIFIED)));
    assertEquals(
        Tone.GOOD, DiagnosticSummary.tone(false, false, List.of(Health.PASS, Health.INFO)));
  }

  @Test
  public void okColumnsNeedsWidthAndNormalFontScale() {
    assertEquals(1, DiagnosticSummary.okColumns(360, 1f));
    assertEquals(1, DiagnosticSummary.okColumns(559, 1f));
    assertEquals(2, DiagnosticSummary.okColumns(560, 1f));
    assertEquals(2, DiagnosticSummary.okColumns(900, 1.1f));
    assertEquals(1, DiagnosticSummary.okColumns(900, 1.3f));
  }
}
