package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class IslandPageTest {

  private static AgentTaskState.View row(
      String session, String phase, boolean active, long started, long seen, boolean dismissed) {
    return new AgentTaskState.View(
        new AgentTaskState.Key(1, "run", session, 1),
        phase,
        active ? "" : "completed",
        active,
        started,
        seen,
        "act-" + session,
        false,
        dismissed);
  }

  @Test
  public void statusMapsSupportStatesAndTreatsUnknownAsUndetermined() {
    assertEquals(IslandPage.Status.UNSUPPORTED, IslandPage.status(0));
    assertEquals(IslandPage.Status.DENIED, IslandPage.status(1));
    assertEquals(IslandPage.Status.ALLOWED, IslandPage.status(2));
    assertEquals(IslandPage.Status.UNDETERMINED, IslandPage.status(3));
    // 未来新增的未知值不能被当成“已允许”。
    assertEquals(IslandPage.Status.UNDETERMINED, IslandPage.status(42));
  }

  @Test
  public void supportStateKeepsOldModelUndeterminedWhenQueryIsFalse() {
    assertEquals(0, IslandPage.supportState(false, true, true));
    assertEquals(2, IslandPage.supportState(true, true, false));
    assertEquals(2, IslandPage.supportState(true, true, true));
    // 36.1+ 新模型：查询为 false 就是用户关掉了实时活动。
    assertEquals(1, IslandPage.supportState(true, false, true));
    // 36.0 旧模型（colorized 判据）：查询为 false 不能显示成“已关闭”，DSHA 仍会尝试提升。
    assertEquals(3, IslandPage.supportState(true, false, false));
    assertTrue(
        IslandPage.mayPromote(IslandPage.status(IslandPage.supportState(true, false, false))));
    assertFalse(
        IslandPage.mayPromote(IslandPage.status(IslandPage.supportState(true, false, true))));
  }

  @Test
  public void settingsAndPromotionFollowStatus() {
    assertFalse(IslandPage.offersSettings(IslandPage.Status.UNSUPPORTED));
    assertTrue(IslandPage.offersSettings(IslandPage.Status.DENIED));
    assertTrue(IslandPage.offersSettings(IslandPage.Status.UNDETERMINED));
    assertTrue(IslandPage.mayPromote(IslandPage.Status.ALLOWED));
    assertTrue(IslandPage.mayPromote(IslandPage.Status.UNDETERMINED));
    assertFalse(IslandPage.mayPromote(IslandPage.Status.DENIED));
    assertFalse(IslandPage.mayPromote(IslandPage.Status.UNSUPPORTED));
  }

  @Test
  public void levelCountsSkipNonParticipants() {
    List<AgentTaskState.View> rows =
        Arrays.asList(
            row("a", "waiting", true, 1, 1, false),
            row("b", "tool", true, 2, 2, false),
            row("c", "tool", true, 3, 3, false),
            row("d", "reply", true, 4, 4, true),
            row("e", "unknown", true, 5, 5, false),
            row("f", "reply", false, 6, 6, false),
            null);
    assertArrayEquals(new int[] {1, 0, 2, 0, 0}, IslandPage.levelCounts(rows));
    assertArrayEquals(new int[] {0, 0, 0, 0, 0}, IslandPage.levelCounts(null));
  }

  @Test
  public void liveRankIgnoresTerminalGraceRows() {
    assertEquals(
        3,
        IslandPage.liveRank(
            Arrays.asList(
                row("a", "tool", true, 1, 1, false), row("b", "reply", true, 0, 0, false))));
    assertEquals(0, IslandPage.liveRank(Arrays.asList(row("a", "reply", false, 1, 99, false))));
    assertEquals(0, IslandPage.liveRank(null));
  }

  @Test
  public void previewUsesSameRepresentativeAsNotifications() {
    long now = 100_000;
    AgentTaskState.View waiting = row("a", "waiting", true, 50, now, false);
    AgentTaskState.View tool = row("b", "tool", true, 10, now, false);
    AgentTaskState.View hidden = row("c", "reply", true, 5, now, true);
    AgentTaskState.View oldDone = row("d", "reply", false, 1, now - 9_000, false);
    List<AgentTaskState.View> rows = Arrays.asList(tool, waiting, hidden, oldDone);
    IslandPage.Preview preview = IslandPage.preview(rows, now, IslandText.TERMINAL_GRACE_MS);
    assertSame(IslandText.pickForDisplay(rows, now, IslandText.TERMINAL_GRACE_MS), preview.shown());
    assertSame(waiting, preview.shown());
    // 划掉的与保留期已过的都不算“另有”。
    assertEquals(1, preview.others());
  }

  @Test
  public void previewShowsRecentlyFinishedTaskAndThenEmpties() {
    long now = 100_000;
    AgentTaskState.View done = row("a", "reply", false, 1, now - 3_000, false);
    IslandPage.Preview recent =
        IslandPage.preview(List.of(done), now, IslandText.TERMINAL_GRACE_MS);
    assertSame(done, recent.shown());
    assertEquals(0, recent.others());
    assertTrue(
        IslandPage.preview(List.of(done), now + 10_000, IslandText.TERMINAL_GRACE_MS).empty());
    assertTrue(IslandPage.preview(null, now, IslandText.TERMINAL_GRACE_MS).empty());
  }

  @Test
  public void samplesMatchRealCapsuleText() {
    assertEquals("等你确认", IslandPage.sample(5, true));
    assertEquals("正在停止", IslandPage.sample(4, true));
    assertEquals("执行·read", IslandPage.sample(3, true));
    assertEquals("思考中", IslandPage.sample(2, true));
    assertEquals("运行中", IslandPage.sample(1, true));
    assertEquals("Tool read", IslandPage.sample(3, false));
    for (int rank : IslandPage.LEVELS) {
      assertTrue(
          IslandPage.sample(rank, true).codePointCount(0, IslandPage.sample(rank, true).length())
              <= IslandText.ZH_LIMIT);
    }
  }
}
