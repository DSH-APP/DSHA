package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class ExternalWatchPolicyTest {
  private static ExternalFocusEvents.Event event(String packageName, String activity, long time) {
    return new ExternalFocusEvents.Event(time, "wm_resume_activity", packageName, activity);
  }

  private static List<ExternalFocusEvents.Event> sample(
      String packageName, String activity, long time) {
    return List.of(event(packageName, activity, time));
  }

  @Test
  public void identicalSamplesAreUnchangedAndSettled() {
    ExternalFocusEvents.Event page = event("a.b", "a.b.Page", 1000L);
    ExternalWatchPolicy.Outcome outcome =
        ExternalWatchPolicy.analyze(
            List.of(List.of(page), List.of(page), List.of(page), List.of(page)),
            7L,
            "events",
            "",
            2);
    assertEquals(ExternalWatchPolicy.State.UNCHANGED, outcome.state());
    assertTrue(outcome.settled());
    assertFalse(outcome.sawChange());
    assertEquals(4, outcome.samples());
    assertEquals(4, outcome.quietRun());
    assertEquals("a.b/a.b.Page", outcome.foreground().component());
    assertEquals(ExternalFocusEvents.digest(7L, "events", "a.b", "a.b.Page"), outcome.digest());
  }

  @Test
  public void changeFollowedByQuietSamplesSettles() {
    ExternalFocusEvents.Event one = event("a.b", "a.b.One", 1000L);
    ExternalFocusEvents.Event two = event("a.b", "a.b.Two", 2000L);
    ExternalWatchPolicy.Outcome outcome =
        ExternalWatchPolicy.analyze(
            List.of(List.of(one), List.of(one), List.of(two), List.of(two)), 7L, "events", "", 2);
    assertEquals(ExternalWatchPolicy.State.CHANGED, outcome.state());
    assertTrue(outcome.settled());
    assertTrue(outcome.sawChange());
    assertEquals(2, outcome.changedAtSample());
    assertEquals(2, outcome.quietRun());
    assertEquals(1, outcome.newEvents());
    assertEquals("a.b/a.b.Two", outcome.foreground().component());
  }

  @Test
  public void changeStillMovingDoesNotClaimSettled() {
    ExternalWatchPolicy.Outcome outcome =
        ExternalWatchPolicy.analyze(
            List.of(
                sample("a.b", "a.b.One", 1000L),
                sample("a.b", "a.b.Two", 2000L),
                sample("a.b", "a.b.Three", 3000L)),
            7L,
            "events",
            "",
            2);
    assertEquals(ExternalWatchPolicy.State.CHANGED, outcome.state());
    assertFalse(outcome.settled());
    assertEquals(1, outcome.changedAtSample());
    assertEquals(1, outcome.quietRun());
    assertEquals("a.b/a.b.Three", outcome.foreground().component());
  }

  @Test
  public void sinceMismatchReportsChangeOnFirstSample() {
    String since = ExternalFocusEvents.digest(7L, "events", "a.b", "a.b.Old");
    ExternalWatchPolicy.Outcome outcome =
        ExternalWatchPolicy.analyze(
            List.of(sample("a.b", "a.b.New", 1000L), sample("a.b", "a.b.New", 1001L)),
            7L,
            "events",
            since,
            2);
    assertEquals(ExternalWatchPolicy.State.CHANGED, outcome.state());
    assertEquals(0, outcome.changedAtSample());
    assertTrue(outcome.settled());
  }

  @Test
  public void sinceMatchStaysUnchanged() {
    String since = ExternalFocusEvents.digest(7L, "events", "a.b", "a.b.Page");
    ExternalWatchPolicy.Outcome outcome =
        ExternalWatchPolicy.analyze(
            List.of(sample("a.b", "a.b.Page", 1000L), sample("a.b", "a.b.Page", 1000L)),
            7L,
            "events",
            since,
            2);
    assertEquals(ExternalWatchPolicy.State.UNCHANGED, outcome.state());
    assertFalse(outcome.sawChange());
  }

  @Test
  public void unreadableSamplesStayUncertain() {
    ExternalWatchPolicy.Outcome outcome =
        ExternalWatchPolicy.analyze(java.util.Arrays.asList(null, null), 7L, "events", "", 2);
    assertEquals(ExternalWatchPolicy.State.UNCERTAIN, outcome.state());
    assertFalse(outcome.settled());
    assertEquals(0, outcome.samples());
    assertTrue(outcome.partial());
    assertEquals("", outcome.digest());

    ExternalWatchPolicy.Outcome single =
        ExternalWatchPolicy.analyze(
            java.util.Arrays.asList(sample("a.b", "a.b.Page", 1000L), null), 7L, "events", "", 2);
    assertEquals(ExternalWatchPolicy.State.UNCERTAIN, single.state());
    assertEquals(1, single.samples());
    assertTrue(single.partial());
  }

  @Test
  public void missingMiddleSampleIsPartialButStillUsable() {
    ExternalWatchPolicy.Outcome outcome =
        ExternalWatchPolicy.analyze(
            java.util.Arrays.asList(
                sample("a.b", "a.b.One", 1000L),
                null,
                sample("a.b", "a.b.Two", 2000L),
                sample("a.b", "a.b.Two", 2001L)),
            7L,
            "events",
            "",
            2);
    assertEquals(ExternalWatchPolicy.State.CHANGED, outcome.state());
    assertEquals(2, outcome.changedAtSample());
    assertEquals(3, outcome.samples());
    assertTrue(outcome.partial());
    assertTrue(outcome.settled());
  }

  @Test
  public void sinceMustBeLowercaseHashOrEmpty() {
    assertTrue(ExternalWatchPolicy.validSince(""));
    assertTrue(ExternalWatchPolicy.validSince(null));
    assertTrue(ExternalWatchPolicy.validSince("0123456789abcdef0123456789abcdef"));
    assertFalse(ExternalWatchPolicy.validSince("0123456789ABCDEF0123456789ABCDEF"));
    assertFalse(ExternalWatchPolicy.validSince("not-a-digest"));
    assertFalse(ExternalWatchPolicy.validSince("short"));
  }
}
