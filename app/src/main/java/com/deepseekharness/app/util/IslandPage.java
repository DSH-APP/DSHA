package com.deepseekharness.app.util;

import java.util.List;

/**
 * 「任务状态上岛」设置页的纯展示逻辑（docs/design/island-status-20261102 方案 B）。
 *
 * <p>不导入任何 Android API。系统能力由调用方查好后传入；岛位选举与保留期完全沿用 {@link IslandText}，
 * 页面预览与真实通知看到的是同一个代表，不另起一套规则。
 */
public final class IslandPage {
  /** 系统状态，与 {@code IslandSupport.STATE_*} 一一对应，但把 36.0 的“查不到”单独命名。 */
  public enum Status {
    /** 支持且已允许提升。 */
    ALLOWED,
    /** 36.1+：系统支持，但用户为本应用关掉了实时活动。 */
    DENIED,
    /** 36.0 旧接口：没有权限查询，DSHA 仍会按 colorized 请求提升。 */
    UNDETERMINED,
    /** 低于 Android 16。 */
    UNSUPPORTED
  }

  /** 页面列出的上岛级别，从高到低；数值与 {@link IslandText#rank} 一致。 */
  public static final int[] LEVELS = {5, 4, 3, 2, 1};

  private IslandPage() {}

  /** 把 {@code IslandSupport.state()} 的整数映射为页面状态；未知值按“查不到”处理，不宣称已允许。 */
  public static Status status(int supportState) {
    return switch (supportState) {
      case 0 -> Status.UNSUPPORTED;
      case 1 -> Status.DENIED;
      case 2 -> Status.ALLOWED;
      default -> Status.UNDETERMINED;
    };
  }

  /**
   * {@code IslandSupport.state()} 的判定（0 不支持 / 1 已关闭 / 2 已允许 / 3 查不到）。
   *
   * <p>{@code canPostPromotedNotifications()} 自 API 36 起存在，但只反映新模型（请求位）的许可；
   * 36.0 旧模型按 colorized 提升，查询为 false 时不能断言用户已关闭，只能是“查不到”。
   */
  public static int supportState(boolean supported, boolean canPost, boolean newPromotionModel) {
    if (!supported) return 0;
    if (canPost) return 2;
    return newPromotionModel ? 1 : 3;
  }

  /** 状态行是否给出“去系统设置”入口：不支持的系统没有对应设置页可开。 */
  public static boolean offersSettings(Status status) {
    return status != Status.UNSUPPORTED;
  }

  /** 系统状态下胶囊是否可能出现（已允许或无法查询）；用于预览里区分“会显示”与“只会是普通通知”。 */
  public static boolean mayPromote(Status status) {
    return status == Status.ALLOWED || status == Status.UNDETERMINED;
  }

  /** 每个级别（5..1）当前有几个参与选举的任务；下标与 {@link #LEVELS} 对应。 */
  public static int[] levelCounts(List<AgentTaskState.View> rows) {
    int[] counts = new int[LEVELS.length];
    if (rows == null) return counts;
    for (AgentTaskState.View row : rows) {
      int rank = IslandText.rank(row);
      if (rank < 1 || rank > 5) continue;
      counts[LEVELS.length - rank]++;
    }
    return counts;
  }

  /**
   * 当前占岛的级别；没有活跃代表（含只剩保留期内的完成态）时返回 0。
   *
   * <p>页面只高亮活跃任务的级别：完成态不属于任何一级。
   */
  public static int liveRank(List<AgentTaskState.View> rows) {
    return IslandText.rank(IslandText.pick(rows));
  }

  /**
   * 页面顶部预览的内容。
   *
   * @param shown 会占用岛位的那条任务；没有时为 null
   * @param others 同时可见、但只会显示普通通知的其他任务数
   */
  public record Preview(AgentTaskState.View shown, int others) {
    public boolean empty() {
      return shown == null;
    }
  }

  /**
   * 回到桌面后会看到什么：代表用 {@link IslandText#pickForDisplay}，与通知渲染同一判据；
   * 其余可见任务只计数（已划掉或保留期已过的不算）。
   */
  public static Preview preview(List<AgentTaskState.View> rows, long now, long graceMs) {
    AgentTaskState.View shown = IslandText.pickForDisplay(rows, now, graceMs);
    if (shown == null || rows == null) return new Preview(null, 0);
    int visible = 0;
    for (AgentTaskState.View row : rows) {
      if (row == null || row.dismissed()) continue;
      if (!row.active()) {
        long age = now - row.seen();
        if (age < 0 || age > graceMs) continue;
      }
      visible++;
    }
    return new Preview(shown, Math.max(0, visible - 1));
  }

  /**
   * 级别示例胶囊：直接复用 {@link IslandText#shortText}，保证页面示例与真实胶囊同源。
   *
   * @param chinese 与通知侧一致，只区分中文与其他（胶囊短文本只有中英两套）
   */
  public static String sample(int rank, boolean chinese) {
    String phase =
        switch (rank) {
          case 5 -> "waiting";
          case 4 -> "stop-requested";
          case 3 -> "tool";
          case 2 -> "reply";
          default -> "running";
        };
    AgentTaskState.View row =
        new AgentTaskState.View(
            new AgentTaskState.Key(0, "", "", 0),
            phase,
            "",
            true,
            0,
            0,
            "",
            false,
            false,
            rank == 3 ? "read · file" : "",
            "",
            0);
    return IslandText.shortText(row, chinese);
  }
}
