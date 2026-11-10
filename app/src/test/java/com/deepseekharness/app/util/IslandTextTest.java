package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class IslandTextTest {

  private static AgentTaskState.View row(
      String session, String phase, boolean active, long started, boolean stopping) {
    return new AgentTaskState.View(
        new AgentTaskState.Key(1, "run", session, 1),
        phase,
        "",
        active,
        started,
        started,
        "act-" + session,
        stopping,
        false);
  }

  @Test
  public void clipHonoursCapsuleLimitsAndNeverReturnsNull() {
    assertEquals("", IslandText.clip(null, true));
    assertEquals("", IslandText.clip("   ", true));
    assertEquals("等你确认", IslandText.clip("等你确认", true));
    assertEquals("1234567", IslandText.clip("1234567890", true));
    assertEquals("123456789", IslandText.clip("1234567890", false));
    // 边界值本身不裁剪。
    assertEquals(7, IslandText.clip("一二三四五六七", true).length());
    assertEquals(9, IslandText.clip("abcdefghi", false).length());
  }

  @Test
  public void quickOptionCountCapsNotificationActionsAtTwo() {
    assertEquals(0, IslandText.quickOptionCount(0));
    assertEquals(1, IslandText.quickOptionCount(1));
    assertEquals(2, IslandText.quickOptionCount(2));
    assertEquals(2, IslandText.quickOptionCount(3));
    assertEquals(2, IslandText.quickOptionCount(99));
    assertEquals(0, IslandText.quickOptionCount(-1));
    assertEquals(2, IslandText.QUICK_OPTION_LIMIT);
  }

  @Test
  public void quickSlotsNeverExceedThreeActionsAndKeepAnAnswerPath() {
    for (int options = 0; options <= 5; options++)
      for (boolean multi : new boolean[] {false, true})
        for (boolean chosen : new boolean[] {false, true}) {
          var slots = IslandText.quickSlots(options, multi, chosen);
          String where = options + "/" + multi + "/" + chosen;
          assertTrue(where, slots.count() <= IslandText.ACTION_LIMIT);
          // 始终留有作答出口：选项、提交、自己写或打开回答至少一个。
          assertTrue(where, slots.count() >= 1);
          if (!multi) assertFalse(where, slots.submit());
        }
  }

  @Test
  public void quickSlotsMatchDocumentedLayouts() {
    // 单选 3 个选项：2 个选项 + 自己写。
    assertEquals(
        new IslandText.QuickSlots(2, false, true, false), IslandText.quickSlots(3, false, false));
    // 多选未选：2 个选项 + 自己写；已选：2 个选项 + 提交，自己写让位。
    assertEquals(
        new IslandText.QuickSlots(2, false, true, false), IslandText.quickSlots(2, true, false));
    assertEquals(
        new IslandText.QuickSlots(2, true, false, false), IslandText.quickSlots(3, true, true));
    // 多选 1 个选项已选：选项 + 提交 + 自己写。
    assertEquals(
        new IslandText.QuickSlots(1, true, true, false), IslandText.quickSlots(1, true, true));
    // 没有选项：自己写 + 打开回答。
    assertEquals(
        new IslandText.QuickSlots(0, false, true, true), IslandText.quickSlots(0, false, false));
    assertEquals(
        new IslandText.QuickSlots(0, false, true, true), IslandText.quickSlots(0, true, true));
  }

  @Test
  public void rankExcludesTerminalDismissedAndUnknownRows() {
    assertEquals(0, IslandText.rank(null));
    assertEquals(0, IslandText.rank(row("a", "waiting", false, 1, false)));
    assertEquals(0, IslandText.rank(row("a", "unknown", true, 1, false)));
    AgentTaskState.View dismissed =
        new AgentTaskState.View(
            new AgentTaskState.Key(1, "run", "a", 1), "waiting", "", true, 1, 1, "x", false, true);
    assertEquals(0, IslandText.rank(dismissed));
  }

  @Test
  public void waitingOutranksRunningAndStoppingOutranksTool() {
    assertTrue(
        IslandText.rank(row("a", "waiting", true, 1, false))
            > IslandText.rank(row("a", "tool", true, 1, false)));
    assertTrue(
        IslandText.rank(row("a", "tool", true, 1, false))
            > IslandText.rank(row("a", "reply", true, 1, false)));
    // 已经点过停止的任务比普通执行更值得显示。
    assertTrue(
        IslandText.rank(row("a", "tool", true, 1, true))
            > IslandText.rank(row("a", "tool", true, 1, false)));
    assertEquals(
        IslandText.rank(row("a", "stop-requested", true, 1, false)),
        IslandText.rank(row("a", "tool", true, 1, true)));
  }

  @Test
  public void pickReturnsHighestPriorityAndBreaksTiesByEarliestStart() {
    assertNull(IslandText.pick(null));
    assertNull(IslandText.pick(List.of()));
    assertNull(IslandText.pick(List.of(row("done", "ended", false, 1, false))));

    var waiting = row("w", "waiting", true, 50, false);
    var tool = row("t", "tool", true, 10, false);
    assertEquals(waiting, IslandText.pick(List.of(tool, waiting)));

    // 同优先级取更早开始的，保证胶囊文字稳定。
    var older = row("o", "tool", true, 10, false);
    var newer = row("n", "tool", true, 20, false);
    assertEquals(older, IslandText.pick(List.of(newer, older)));
  }

  @Test
  public void tagOfMatchesNotificationTagFormat() {
    assertNull(IslandText.tagOf(null));
    var value = row("s", "tool", true, 1, false);
    assertTrue(IslandText.tagOf(value).startsWith("dsha-task:"));
  }

  private static AgentTaskState.View view(
      String phase,
      String reason,
      boolean active,
      long started,
      long seen,
      boolean stopping,
      String detail) {
    return new AgentTaskState.View(
        new AgentTaskState.Key(1, "run", "s-" + phase + "-" + started, 1),
        phase,
        reason,
        active,
        started,
        seen,
        "act",
        stopping,
        false,
        detail,
        "",
        started);
  }

  @Test
  public void shortTextKeepsToolDetailAndMapsEveryTerminalReason() {
    assertEquals(
        "执行·read",
        IslandText.shortText(view("tool", "", true, 1, 1, false, "read_file · src/a.md"), true));
    assertEquals("执行中", IslandText.shortText(view("tool", "", true, 1, 1, false, ""), true));
    assertEquals("等你确认", IslandText.shortText(view("waiting", "", true, 1, 1, false, ""), true));
    assertEquals("思考中", IslandText.shortText(view("reply", "", true, 1, 1, false, ""), true));
    assertEquals("正在停止", IslandText.shortText(view("tool", "", true, 1, 1, true, ""), true));
    assertEquals(
        "正在停止", IslandText.shortText(view("stop-requested", "", true, 1, 1, false, ""), true));
    assertEquals(
        "完成", IslandText.shortText(view("ended", "completed", false, 1, 1, false, ""), true));
    assertEquals(
        "已停止", IslandText.shortText(view("ended", "aborted", false, 1, 1, false, ""), true));
    assertEquals(
        "已达上限", IslandText.shortText(view("ended", "max-tokens", false, 1, 1, false, ""), true));
    assertEquals(
        "已断开",
        IslandText.shortText(view("ended", "connection-lost", false, 1, 1, false, ""), true));
    assertEquals("需处理", IslandText.shortText(view("ended", "error", false, 1, 1, false, ""), true));
    assertEquals(
        "已结束", IslandText.shortText(view("ended", "unknown", false, 1, 1, false, ""), true));
    // 英文胶囊上限 9 字符：工具名超过 4 个 code point 时先截工具名。
    assertEquals(
        "Tool bash", IslandText.shortText(view("tool", "", true, 1, 1, false, "bash · x"), false));
  }

  @Test
  public void completionKeepsTheIslandForTheGraceWindowOnly() {
    var active = view("tool", "", true, 1, 3_000, false, "read_file · a");
    var ended =
        new AgentTaskState.View(
            new AgentTaskState.Key(1, "run", "done", 1),
            "ended",
            "completed",
            false,
            1,
            4_000,
            "act-done",
            false,
            false,
            "",
            "",
            4_000);
    assertEquals(active, IslandText.pickForDisplay(List.of(active, ended), 4_500, 8_000));
    assertEquals(ended, IslandText.pickForDisplay(List.of(ended), 4_500, 8_000));
    assertNull(IslandText.pickForDisplay(List.of(ended), 12_001, 8_000));
    var dismissed =
        new AgentTaskState.View(
            ended.key(),
            ended.phase(),
            ended.reason(),
            false,
            ended.started(),
            ended.seen(),
            ended.action(),
            false,
            true,
            "",
            "",
            ended.phaseAt());
    assertNull(IslandText.pickForDisplay(List.of(dismissed), 4_500, 8_000));
    var older =
        new AgentTaskState.View(
            new AgentTaskState.Key(1, "run", "older", 1),
            "ended",
            "aborted",
            false,
            1,
            3_000,
            "act-older",
            false,
            false,
            "",
            "",
            3_000);
    assertEquals(ended, IslandText.pickForDisplay(List.of(older, ended), 4_500, 8_000));
    assertEquals(8_000L, IslandText.TERMINAL_GRACE_MS);
  }

  @Test
  public void durationUsesMinutesAndHoursWithoutNinetyFiveMinuteStrings() {
    assertEquals("0s", IslandText.duration(0, true));
    assertEquals("59s", IslandText.duration(59_999, true));
    assertEquals("1分", IslandText.duration(60_000, true));
    assertEquals("1分35秒", IslandText.duration(95_000, true));
    assertEquals("1m35s", IslandText.duration(95_000, false));
    assertEquals("1小时00分", IslandText.duration(3_600_000, true));
    assertEquals("1小时05分", IslandText.duration(3_900_000, true));
    assertEquals("1h05m", IslandText.duration(3_900_000, false));
    assertEquals("42s", IslandText.duration(42_000, false));
  }

  @Test
  public void clipNeverSplitsSurrogatePairs() {
    String emoji = "\uD83D\uDE00";
    String nine = emoji.repeat(9);
    String clipped = IslandText.clip(nine, true);
    assertEquals(7, clipped.codePointCount(0, clipped.length()));
    assertEquals(14, clipped.length());
  }
}
