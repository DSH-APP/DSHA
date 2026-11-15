package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** 增量判定只决定“回全量 / 回差异 / 回相同”，不授予任何动作凭据；边界与回退原因都要可核对。 */
public final class ScreenSenseDeltaPolicyTest {
  private static final ScreenTarget TARGET = new ScreenTarget(0, 7, "example.app");

  private static ScreenSenseState.Element element(String id, String label) {
    return new ScreenSenseState.Element(id, "T", label, "", "", false, 0, 0, 0, 0);
  }

  private static ScreenSenseState state(
      long sid, String scope, ScreenTarget target, int width, List<ScreenSenseState.Element> rows) {
    return new ScreenSenseState(
        sid, scope, target, width, 2400, false, -1, "tree", rows.size(), false, "", rows);
  }

  private static ScreenSenseState state(
      long sid, String scope, List<ScreenSenseState.Element> rows) {
    return state(sid, scope, TARGET, 1080, rows);
  }

  @Test
  public void explicitFullRequestAndMissingPreviousAlwaysReturnFull() {
    ScreenSenseState current = state(2, "scope", List.of(element("k000", "a")));
    assertEquals(
        ScreenSenseDeltaPolicy.Mode.FULL,
        ScreenSenseDeltaPolicy.decide(current, current, true).mode());
    assertEquals("FULL_REQUESTED", ScreenSenseDeltaPolicy.decide(current, current, true).reason());
    assertEquals("NO_PREVIOUS", ScreenSenseDeltaPolicy.decide(null, current, false).reason());
  }

  @Test
  public void unchangedScreenReturnsSameWithoutCodecLines() {
    List<ScreenSenseState.Element> rows = List.of(element("k000", "a"), element("k001", "b"));
    var decision =
        ScreenSenseDeltaPolicy.decide(state(1, "scope", rows), state(2, "scope", rows), false);
    assertEquals(ScreenSenseDeltaPolicy.Mode.SAME, decision.mode());
    assertEquals("UNCHANGED", decision.reason());
    assertEquals(0, decision.delta().changedCount);
    String encoded = ScreenSenseCodec.encodeDelta(decision.delta(), state(2, "scope", rows));
    assertTrue(encoded.startsWith("D 2 1 0 1080x2400"));
    assertEquals(1, encoded.split("\n").length);
  }

  @Test
  public void changedWithinOneThirdReturnsOnlyChangedLines() {
    List<ScreenSenseState.Element> before =
        List.of(
            element("k000", "a"), element("k001", "b"), element("k002", "c"), element("k003", "d"));
    List<ScreenSenseState.Element> after =
        List.of(
            element("k000", "a"),
            element("k001", "b2"),
            element("k002", "c"),
            element("k003", "d"));
    var decision =
        ScreenSenseDeltaPolicy.decide(state(1, "scope", before), state(2, "scope", after), false);
    assertEquals(ScreenSenseDeltaPolicy.Mode.DELTA, decision.mode());
    assertEquals("WITHIN_THRESHOLD", decision.reason());
    assertEquals(1, decision.delta().changedCount);
    String encoded = ScreenSenseCodec.encodeDelta(decision.delta(), state(2, "scope", after));
    assertTrue(encoded.contains("~k001 T \"b2\""));
    assertFalse(encoded.contains("k000"));
  }

  @Test
  public void exactlyOneThirdIsStillADeltaAndMoreFallsBackToFull() {
    List<ScreenSenseState.Element> before =
        List.of(element("k000", "a"), element("k001", "b"), element("k002", "c"));
    List<ScreenSenseState.Element> oneThird =
        List.of(element("k000", "a2"), element("k001", "b"), element("k002", "c"));
    assertEquals(
        ScreenSenseDeltaPolicy.Mode.DELTA,
        ScreenSenseDeltaPolicy.decide(state(1, "scope", before), state(2, "scope", oneThird), false)
            .mode());
    List<ScreenSenseState.Element> twoThirds =
        List.of(element("k000", "a2"), element("k001", "b2"), element("k002", "c"));
    var decision =
        ScreenSenseDeltaPolicy.decide(
            state(1, "scope", before), state(2, "scope", twoThirds), false);
    assertEquals(ScreenSenseDeltaPolicy.Mode.FULL, decision.mode());
    assertEquals("CHANGED_OVER_THIRD", decision.reason());
  }

  @Test
  public void scopeTargetFrameAndSidChangesAlwaysFallBackToFull() {
    List<ScreenSenseState.Element> rows = List.of(element("k000", "a"));
    assertEquals(
        "SCOPE_CHANGED",
        ScreenSenseDeltaPolicy.decide(state(1, "scope-a", rows), state(2, "scope-b", rows), false)
            .reason());
    assertEquals(
        "TARGET_CHANGED",
        ScreenSenseDeltaPolicy.decide(
                state(1, "scope", new ScreenTarget(0, 7, "example.app"), 1080, rows),
                state(2, "scope", new ScreenTarget(0, 8, "example.app"), 1080, rows),
                false)
            .reason());
    assertEquals(
        "FRAME_CHANGED",
        ScreenSenseDeltaPolicy.decide(
                state(1, "scope", rows), state(2, "scope", TARGET, 1081, rows), false)
            .reason());
    assertEquals(
        "SID_NOT_MONOTONIC",
        ScreenSenseDeltaPolicy.decide(state(2, "scope", rows), state(2, "scope", rows), false)
            .reason());
  }

  @Test
  public void codecOverloadMatchesThePairEncoder() {
    List<ScreenSenseState.Element> before = List.of(element("k000", "a"));
    List<ScreenSenseState.Element> after = List.of(element("k000", "b"));
    var previous = state(1, "scope", before);
    var current = state(2, "scope", after);
    String pair = ScreenSenseCodec.encodeDelta(previous, current);
    String precomputed =
        ScreenSenseCodec.encodeDelta(ScreenSenseCodec.diff(previous, current), current);
    assertEquals(pair, precomputed);
    try {
      ScreenSenseCodec.encodeDelta((ScreenSenseCodec.Delta) null, current);
      fail("null delta must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_SENSE_DIFF_INVALID", expected.getMessage());
    }
  }
}
