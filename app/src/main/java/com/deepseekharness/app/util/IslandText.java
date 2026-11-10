package com.deepseekharness.app.util;

import java.util.List;

/**
 * 任务状态上岛的纯逻辑：岛位选举与短文案裁剪。
 *
 * <p>不导入任何 Android API，便于在宿主 JVM 上直接单测。真正的通知增强由
 * {@code com.deepseekharness.app.IslandSupport} 负责。
 *
 * <p>为什么需要选举：系统按包名只显示一个胶囊（SystemUI {@code filterByPackage}），
 * 多个活跃任务同时请求提升会互相顶掉，表现为胶囊文字乱跳。这里按
 * 「等待确认 &gt; 正在停止 &gt; 执行工具 &gt; 生成回复 &gt; 其他」选出唯一代表。
 */
public final class IslandText {
  /** 胶囊最大宽度约 96dp，实测中文约 7 字、英文约 9 字符即会开始截断。 */
  public static final int ZH_LIMIT = 7;

  public static final int EN_LIMIT = 9;

  /** 系统通知最多显示 3 个动作：选项最多 2 个，最后一个槽位留给提交/自己写。 */
  public static final int QUICK_OPTION_LIMIT = 2;

  /** 完成态在岛上保留的时长：promoted 通知必须保持 ongoing，之后再主动取消。 */
  public static final long TERMINAL_GRACE_MS = 8_000;

  private IslandText() {}

  /** 通知上实际显示的选项数：0..2；超出的选项由“点按通知进入对话”承接。 */
  public static int quickOptionCount(int options) {
    return Math.max(0, Math.min(QUICK_OPTION_LIMIT, options));
  }

  /** 系统通知可见的动作上限。 */
  public static final int ACTION_LIMIT = 3;

  /** 等待作答时通知动作的槽位规划：选项数、是否放提交 / 自己写 / 打开回答。 */
  public record QuickSlots(int options, boolean submit, boolean custom, boolean openApp) {
    /** 实际占用的动作数，恒不超过 {@link #ACTION_LIMIT}。 */
    public int count() {
      return options + (submit ? 1 : 0) + (custom ? 1 : 0) + (openApp ? 1 : 0);
    }
  }

  /**
   * 按系统 3 个动作槽位规划等待作答通知：最多 2 个选项；多选已选时放「提交」，余位才放「自己写」；
   * 没有可点选项时补「打开回答」。超出的选项由点按通知进入对话承接。
   */
  public static QuickSlots quickSlots(int options, boolean multi, boolean anyChosen) {
    int shown = quickOptionCount(options);
    boolean submit = multi && anyChosen && shown > 0;
    boolean custom = shown + (submit ? 1 : 0) < ACTION_LIMIT;
    boolean openApp = shown == 0 && shown + (custom ? 1 : 0) < ACTION_LIMIT;
    return new QuickSlots(shown, submit, custom, openApp);
  }

  /** 裁剪胶囊短文本；null/空返回空串。按 code point 裁剪，不会切出半个代理对。 */
  public static String clip(String text, boolean chinese) {
    if (text == null) return "";
    String value = text.trim();
    int limit = chinese ? ZH_LIMIT : EN_LIMIT;
    if (value.codePointCount(0, value.length()) <= limit) return value;
    return value.substring(0, value.offsetByCodePoints(0, limit));
  }

  /**
   * 岛的候选优先级；0 表示不参与（终态、被划掉、状态不明）。
   *
   * <p>正在停止的任务排在执行前：用户已经点了停止，这时岛上更应该显示「正在停止」。
   */
  public static int rank(AgentTaskState.View row) {
    if (row == null || !row.active() || row.dismissed()) return 0;
    if ("unknown".equals(row.phase())) return 0;
    if (row.stopping() || "stop-requested".equals(row.phase())) return 4;
    return switch (row.phase()) {
      case "waiting" -> 5;
      case "tool" -> 3;
      case "reply" -> 2;
      default -> 1;
    };
  }

  /**
   * 选出当前应该占用岛位的任务；没有候选时返回 null。
   *
   * <p>同优先级时先开始的任务优先，保证胶囊文字稳定，不会来回跳。
   */
  public static AgentTaskState.View pick(List<AgentTaskState.View> rows) {
    if (rows == null) return null;
    AgentTaskState.View best = null;
    int bestRank = 0;
    for (AgentTaskState.View row : rows) {
      int rank = rank(row);
      if (rank == 0) continue;
      if (best == null || rank > bestRank || (rank == bestRank && row.started() < best.started())) {
        best = row;
        bestRank = rank;
      }
    }
    return best;
  }

  /** 通知 tag 与岛位的对应关系；选中任务对应唯一 tag。 */
  public static String tagOf(AgentTaskState.View row) {
    return row == null ? null : "dsha-task:" + row.key();
  }

  /**
   * 胶囊短文本：工具阶段优先显示“执行·工具名”，其余显示阶段词；终态显示完成结果。
   *
   * <p>系统状态栏胶囊建议不超过 7 个字符，放不下时只显示前几个字；这里统一裁剪，
   * 超过限长的文案由 {@link #clip} 处理，不做二次拼接。
   */
  public static String shortText(AgentTaskState.View row, boolean chinese) {
    if (row == null) return "";
    String text;
    switch (row.phase()) {
      case "ended":
        text = endedText(row.reason(), chinese);
        break;
      case "waiting":
        text = chinese ? "等你确认" : "Needs you";
        break;
      case "stop-requested":
        text = chinese ? "正在停止" : "Stopping";
        break;
      case "tool":
        String tool = toolName(row.detail());
        text =
            tool.isEmpty()
                ? (chinese ? "执行中" : "Working")
                : (chinese ? "执行·" + tool : "Tool " + tool);
        break;
      case "reply":
        text = chinese ? "思考中" : "Thinking";
        break;
      case "unknown":
        text = chinese ? "状态未知" : "Unknown";
        break;
      default:
        text = chinese ? "运行中" : "Running";
    }
    if (row.stopping() && !"stop-requested".equals(row.phase()))
      text = chinese ? "正在停止" : "Stopping";
    return clip(text, chinese);
  }

  private static String endedText(String reason, boolean chinese) {
    return switch (reason == null ? "" : reason) {
      case "completed" -> chinese ? "完成" : "Done";
      case "aborted", "interrupted" -> chinese ? "已停止" : "Stopped";
      case "max-tokens" -> chinese ? "已达上限" : "Limit";
      case "connection-lost" -> chinese ? "已断开" : "Offline";
      case "blocked", "error" -> chinese ? "需处理" : "Attention";
      default -> chinese ? "已结束" : "Ended";
    };
  }

  /** 从 “工具名 · 目标” 里取工具名，供 7 字胶囊使用。 */
  static String toolName(String detail) {
    if (detail == null) return "";
    int separator = detail.indexOf(" · ");
    String name = (separator >= 0 ? detail.substring(0, separator) : detail).trim();
    int space = name.indexOf(' ');
    if (space >= 0) name = name.substring(0, space);
    if (name.codePointCount(0, name.length()) > 4)
      name = name.substring(0, name.offsetByCodePoints(0, 4));
    return name;
  }

  /**
   * 岛位选举（含完成态）：有活跃任务时沿用 {@link #pick}；全部结束时，
   * 最近 {@code graceMs} 内结束且未被划掉的任务短暂占用岛位，让用户看到“完成/已停止”，
   * 而不是胶囊直接消失。结束时间取 {@code seen}（即 turn/end 事件时间）。
   */
  public static AgentTaskState.View pickForDisplay(
      List<AgentTaskState.View> rows, long now, long graceMs) {
    AgentTaskState.View active = pick(rows);
    if (active != null) return active;
    if (rows == null) return null;
    AgentTaskState.View recent = null;
    for (AgentTaskState.View row : rows) {
      if (row.active() || row.dismissed()) continue;
      long age = now - row.seen();
      if (age < 0 || age > graceMs) continue;
      if (recent == null || row.seen() > recent.seen()) recent = row;
    }
    return recent;
  }

  /** 耗时格式：<60s 用秒，之后用分秒；小时级不输出“95分”这种形式。 */
  public static String duration(long ms, boolean chinese) {
    long seconds = Math.max(0, ms) / 1000;
    if (seconds < 60) return seconds + "s";
    long minutes = seconds / 60;
    long rest = seconds % 60;
    if (minutes < 60) {
      if (chinese) return rest == 0 ? minutes + "分" : minutes + "分" + rest + "秒";
      return rest == 0 ? minutes + "m" : minutes + "m" + rest + "s";
    }
    long hours = minutes / 60;
    long remain = minutes % 60;
    String tail = remain < 10 ? "0" + remain : Long.toString(remain);
    return chinese ? hours + "小时" + tail + "分" : hours + "h" + tail + "m";
  }
}
