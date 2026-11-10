package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class AgentTaskStateTest {
  @Test
  public void snapshotsAreOrderedImmutableAndDetachedFromLaterChanges() {
    var state = new AgentTaskState();
    state.identity(1, "run");
    String client = "a".repeat(32);
    state.bind(client);
    var empty = state.snapshot(0);
    assertFalse(empty.contains(null));
    assertThrows(UnsupportedOperationException.class, () -> empty.add(null));
    var first = new AgentTaskState.Key(1, "run", "first", 1);
    var second = new AgentTaskState.Key(1, "run", "second", 1);
    assertTrue(state.event(client, first, 1, "reply", "", 100));
    assertTrue(state.event(client, second, 1, "tool", "", 101));
    var captured = state.snapshot(101);
    assertEquals(0, empty.size());
    assertEquals(2, captured.size());
    assertFalse(captured.contains(null));
    assertEquals(first, captured.get(0).key());
    assertEquals(second, captured.get(1).key());
    assertThrows(UnsupportedOperationException.class, () -> captured.clear());
    assertThrows(UnsupportedOperationException.class, () -> captured.set(0, null));
    assertTrue(state.event(client, first, 2, "ended", "completed", 102));
    var third = new AgentTaskState.Key(1, "run", "third", 1);
    assertTrue(state.event(client, third, 1, "running", "", 103));
    assertEquals(2, captured.size());
    assertEquals("reply", captured.get(0).phase());
    assertEquals("", captured.get(0).reason());
    assertTrue(captured.get(0).active());
    assertEquals(3, state.snapshot(103).size());
    assertEquals("completed", state.snapshot(103).get(0).reason());
    assertFalse(state.snapshot(103).get(0).active());
  }

  @Test
  public void sameSessionTurnResetHasSeparateOwnerAndCannotClaimOldAction() {
    var state = new AgentTaskState();
    state.identity(1, "run");
    state.bind("a".repeat(32));
    var old = new AgentTaskState.Key(1, "run", "session", 8, "old-owner");
    state.event("a".repeat(32), old, 1, "running", "", 0);
    var row = state.snapshot(0).get(0);
    state.requestStop(old, row.action(), 0);
    var fresh = new AgentTaskState.Key(1, "run", "session", 1, "new-owner");
    assertTrue(state.event("a".repeat(32), fresh, 2, "running", "", 1));
    assertNull(state.claim("a".repeat(32), 1, "run"));
    assertFalse(state.requestStop(old, row.action(), 1));
    assertFalse(state.event("a".repeat(32), old, 1, "ended", "completed", 2));
    assertEquals("unknown", state.snapshot(2).get(0).phase());
  }

  @Test
  public void dismissedTaskDoesNotRepostAndReporterReplacementNeedsStaleFacts() {
    var state = new AgentTaskState();
    state.identity(1, "run");
    state.bind("e".repeat(32));
    var key = new AgentTaskState.Key(1, "run", "session", 1);
    state.event("e".repeat(32), key, 1, "running", "", 0);
    var row = state.snapshot(0).get(0);
    assertTrue(state.dismiss(key, row.action()));
    assertTrue(state.snapshot(0).get(0).dismissed());
    assertFalse(state.bind("f".repeat(32), 14_000));
    assertTrue(state.bind("f".repeat(32), 16_000));
    assertFalse(state.matches(1, "run", "e".repeat(32)));
    assertFalse(state.snapshot(16_000).get(0).active());
    assertEquals("unknown", state.snapshot(16_000).get(0).phase());
  }

  @Test
  public void onlyCurrentTurnCanClaimOnceAndAckIsNotCompletion() {
    var state = new AgentTaskState();
    state.identity(1, "run");
    assertTrue(state.bind("a".repeat(32)));
    var key = new AgentTaskState.Key(1, "run", "session", 2);
    assertTrue(state.event("a".repeat(32), key, 1, "running", "", 100));
    var row = state.snapshot(100).get(0);
    assertTrue(state.requestStop(key, row.action(), 100));
    assertFalse(state.requestStop(key, row.action(), 100));
    assertNotNull(state.claim("a".repeat(32), 1, "run"));
    assertNull(state.claim("a".repeat(32), 1, "run"));
    assertTrue(state.ack("a".repeat(32), key, row.action(), "requested", 200));
    assertTrue(state.snapshot(200).get(0).active());
    assertEquals("stop-requested", state.snapshot(200).get(0).phase());
    assertTrue(state.event("a".repeat(32), key, 2, "ended", "aborted", 300));
    assertFalse(state.snapshot(300).get(0).active());
    assertFalse(state.event("a".repeat(32), key, 3, "running", "", 400));
  }

  @Test
  public void connectionLossAndMissingAckStayUnknownWithoutReplay() {
    var state = new AgentTaskState();
    state.identity(1, "run");
    state.bind("b".repeat(32));
    var key = new AgentTaskState.Key(1, "run", "session", 1);
    state.event("b".repeat(32), key, 1, "tool", "", 0);
    var row = state.snapshot(0).get(0);
    state.requestStop(key, row.action(), 0);
    state.claim("b".repeat(32), 1, "run");
    state.event("b".repeat(32), key, 2, "running", "", 10_001);
    assertEquals("unknown", state.snapshot(10_001).get(0).phase());
    assertNull(state.claim("b".repeat(32), 1, "run"));
    state.identity(2, "new");
    assertFalse(state.requestStop(key, row.action(), 10_002));
    assertFalse(state.event("b".repeat(32), key, 3, "ended", "completed", 10_002));
  }

  @Test
  public void sequencesAndSourceIdentityAreStrict() {
    var state = new AgentTaskState();
    state.identity(1, "run");
    state.bind("c".repeat(32));
    var key = new AgentTaskState.Key(1, "run", "session", 3);
    assertFalse(state.event("d".repeat(32), key, 1, "running", "", 0));
    assertTrue(state.event("c".repeat(32), key, 5, "reply", "", 0));
    assertFalse(state.event("c".repeat(32), key, 4, "ended", "completed", 0));
    assertFalse(
        state.event(
            "c".repeat(32),
            new AgentTaskState.Key(1, "run", "session", 2),
            6,
            "ended",
            "completed",
            0));
    assertEquals("unknown", state.snapshot(15_001).get(0).phase());
  }

  @Test
  public void detailAndTitleAreOneLineBoundedAndRefreshPhaseTiming() {
    var state = new AgentTaskState();
    state.identity(1, "run");
    state.bind("a".repeat(32));
    var key = new AgentTaskState.Key(1, "run", "session", 1);
    assertTrue(
        state.event(
            "a".repeat(32),
            key,
            1,
            "tool",
            "",
            "read_file · src/README.md\n第二阶段",
            "会话标题\n第二行",
            100));
    var row = state.snapshot(100).get(0);
    assertEquals("read_file · src/README.md 第二阶段", row.detail());
    assertEquals("会话标题 第二行", row.title());
    assertEquals(100, row.phaseAt());
    assertTrue(
        state.event("a".repeat(32), key, 2, "tool", "", "read_file · src/OTHER.md", "", 200));
    assertEquals(200, state.snapshot(200).get(0).phaseAt());
    assertTrue(
        state.event("a".repeat(32), key, 3, "tool", "", "read_file · src/OTHER.md", "", 300));
    assertEquals(200, state.snapshot(300).get(0).phaseAt());
    String longDetail = "x".repeat(200);
    assertTrue(state.event("a".repeat(32), key, 4, "reply", "", longDetail, "", 400));
    assertEquals(64, state.snapshot(400).get(0).detail().length());
  }

  @Test
  public void silenceEndsWithConnectionLostAndAHeartbeatCanReviveIt() {
    var state = new AgentTaskState();
    state.identity(1, "run");
    String client = "a".repeat(32);
    state.bind(client);
    var key = new AgentTaskState.Key(1, "run", "session", 1);
    assertTrue(state.event(client, key, 1, "tool", "", 5_000));
    assertEquals(15_000, AgentTaskState.STALE_UNKNOWN_MS);
    assertEquals(60_000, AgentTaskState.STALE_END_MS);
    var unknown = state.snapshot(20_001).get(0);
    assertEquals("unknown", unknown.phase());
    assertEquals("connection-lost", unknown.reason());
    assertTrue(unknown.active());
    var ended = state.snapshot(65_500).get(0);
    assertEquals("ended", ended.phase());
    assertEquals("connection-lost", ended.reason());
    assertFalse(ended.active());
    // 瞬时断连不是真实终态：同一轮的心跳可以恢复，胶囊重新出现。
    assertTrue(state.event(client, key, 2, "reply", "", 65_600));
    var revived = state.snapshot(65_600).get(0);
    assertTrue(revived.active());
    assertEquals("reply", revived.phase());
    assertTrue(revived.reason().isEmpty());
    // 真实终态仍然不可复活。
    assertTrue(state.event(client, key, 3, "ended", "completed", 65_700));
    assertFalse(state.snapshot(65_700).get(0).active());
    assertFalse(state.event(client, key, 4, "running", "", 65_800));
  }

  @Test
  public void reporterTakeoverKeepsTheRowRevivableButRealTerminalDoesNot() {
    var state = new AgentTaskState();
    state.identity(1, "run");
    var key = new AgentTaskState.Key(1, "run", "session", 1);
    assertTrue(state.bind("b".repeat(32)));
    assertTrue(state.event("b".repeat(32), key, 1, "running", "", 0));
    assertFalse(state.bind("c".repeat(32), 10_000));
    assertTrue(state.bind("c".repeat(32), 16_000));
    var taken = state.snapshot(16_000).get(0);
    assertFalse(taken.active());
    assertEquals("unknown", taken.phase());
    assertEquals("reporter-changed", taken.reason());
    assertTrue(state.event("c".repeat(32), key, 2, "tool", "", 16_001));
    assertTrue(state.snapshot(16_001).get(0).active());
    assertTrue(state.event("c".repeat(32), key, 3, "ended", "max-tokens", 16_002));
    assertFalse(state.snapshot(16_002).get(0).active());
    assertEquals("max-tokens", state.snapshot(16_002).get(0).reason());
    assertFalse(state.event("c".repeat(32), key, 4, "running", "", 16_003));
  }

  @Test
  public void questionPayloadIsBoundedAndClearedByNonWaitingFacts() {
    var state = new AgentTaskState();
    state.identity(1, "run");
    String client = "a".repeat(32);
    state.bind(client);
    var key = new AgentTaskState.Key(1, "run", "session", 1);
    String question = "{\"version\":1,\"questions\":[{\"id\":\"q1\"}]}";
    assertTrue(state.event(client, key, 1, "waiting", "", "问题", "标题", question, 100));
    assertEquals(question, state.snapshot(100).get(0).question());
    assertTrue(state.event(client, key, 2, "running", "", "", "", "", 200));
    assertEquals("", state.snapshot(200).get(0).question());
    String longQuestion = "x".repeat(3000);
    assertTrue(state.event(client, key, 3, "waiting", "", "", "", longQuestion, 300));
    assertEquals(2000, state.snapshot(300).get(0).question().length());
  }
}
