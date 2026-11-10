package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动日志概览：把 {@link StartupTrace} 的结构化行按阶段折叠，计时行降级为阶段明细，问题行上浮。
 *
 * <p>分组只看 {@link StartupTrace.Entry#kind}（阶段开始 / 完成 / 切离），不解析中文文案，中英文共用。
 * 计时行按固定前缀解析，解析失败按普通行保留，不丢行。纯逻辑，不依赖 Android。
 */
public final class StartupLogDigest {
  private StartupLogDigest() {}

  /** 本次启动的整体结果，由界面按真实运行状态给出，不从日志文本推测。 */
  public enum Outcome {
    IDLE,
    RUNNING,
    READY,
    FAILED,
    STOPPED
  }

  public enum State {
    RUNNING,
    GOOD,
    WARN,
    BAD,
    MUTED
  }

  public enum Severity {
    NONE,
    WARN,
    BAD
  }

  public enum ItemKind {
    TIMING,
    NOTE,
    ISSUE
  }

  /** 原始视图的行分类，用于着色与筛选。 */
  public enum LineClass {
    STAGE,
    TIMING,
    NOTE,
    WARN,
    BAD
  }

  public static final class Item {
    public final ItemKind kind;
    public final Severity severity;

    /** 计时：phase；普通行：去掉大写标签后的正文；问题：完整正文。 */
    public final String label;

    /** 计时：耗时毫秒；其余：相对本阶段开始的毫秒（与阶段耗时同口径）。 */
    public final long valueMs;

    /** 自本次启动开始的毫秒数（原始视图口径）。 */
    public final long atMs;

    /** CAS 计时的条目数；没有时为 -1。 */
    public final long count;

    /** 在 entries 中的下标，用于跳到原始视图。 */
    public final int entryIndex;

    Item(
        ItemKind kind,
        Severity severity,
        String label,
        long valueMs,
        long atMs,
        long count,
        int entryIndex) {
      this.kind = kind;
      this.severity = severity;
      this.label = label;
      this.valueMs = valueMs;
      this.atMs = atMs;
      this.count = count;
      this.entryIndex = entryIndex;
    }
  }

  public static final class Stage {
    /** 阶段名；日志开头的隐式阶段在其完成行到达前为 null（界面显示「准备」）。 */
    public final String name;

    public final long startMs;

    /** 阶段耗时；就绪里程碑（没有可度量的停留）为 -1。 */
    public final long durationMs;

    public final State state;
    public final List<Item> items;

    /** 本阶段在 entries 中的首行下标。 */
    public final int firstEntryIndex;

    Stage(
        String name,
        long startMs,
        long durationMs,
        State state,
        List<Item> items,
        int firstEntryIndex) {
      this.name = name;
      this.startMs = startMs;
      this.durationMs = durationMs;
      this.state = state;
      this.items = Collections.unmodifiableList(items);
      this.firstEntryIndex = firstEntryIndex;
    }
  }

  public static final class Result {
    public final Outcome outcome;
    public final List<Stage> stages;

    /** 失败 / 警告行数量（含计时异常）。 */
    public final int issueCount;

    public final Severity worst;
    public final int timingCount;
    public final int lineCount;

    /** 与 entries 同序。 */
    public final List<LineClass> lineClasses;

    Result(
        Outcome outcome,
        List<Stage> stages,
        int issueCount,
        Severity worst,
        int timingCount,
        int lineCount,
        List<LineClass> lineClasses) {
      this.outcome = outcome;
      this.stages = Collections.unmodifiableList(stages);
      this.issueCount = issueCount;
      this.worst = worst;
      this.timingCount = timingCount;
      this.lineCount = lineCount;
      this.lineClasses = Collections.unmodifiableList(lineClasses);
    }
  }

  private static final Pattern PREP =
      Pattern.compile("^STARTUP_PREP_TIMING: phase=(\\S+) durationMs=(\\d+)(?:\\s.*)?$");
  private static final Pattern PREP_OUTCOME = Pattern.compile("\\boutcome=(\\w+)");
  private static final Pattern CAS = Pattern.compile("^CAS_TIMING: (\\{.*\\})$");
  private static final Pattern CAS_PHASE = Pattern.compile("\"phase\"\\s*:\\s*\"([^\"]{1,64})\"");
  private static final Pattern CAS_ELAPSED =
      Pattern.compile("\"elapsedMs\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
  private static final Pattern CAS_ENTRIES = Pattern.compile("\"entries\"\\s*:\\s*(\\d{1,9})");
  private static final Pattern TAGGED =
      Pattern.compile("^([A-Z][A-Z0-9_]{3,})(?:\\[[^\\]]*\\])?: (.*)$", Pattern.DOTALL);
  private static final Pattern BARE_DURATION = Pattern.compile("^\\d+(?:\\.\\d+)?m?s$");

  /** 行首错误标记：只认明确的异常 / 失败开头，避免把「0 errors」之类正常输出当问题。 */
  private static final Pattern ERROR_HEAD =
      Pattern.compile(
          "^(?:[A-Za-z]*Error\\b|FATAL\\b|Fatal\\b|fatal:|Uncaught\\b|Unhandled\\b|panic:|错误[:：]|启动失败)");

  private static final Pattern ERROR_ANYWHERE =
      Pattern.compile("(?:exited with code [1-9]|退出码 [1-9]|在鉴权前退出|exited before auth)");

  /**
   * 按阶段折叠。
   *
   * @param entries {@link StartupTrace.Snapshot#entries}
   * @param outcome 本次启动的真实结果
   * @param endMs 未关闭阶段的结束时刻（自启动开始）；见 {@link #endMs}
   */
  public static Result digest(List<StartupTrace.Entry> entries, Outcome outcome, long endMs) {
    List<StartupTrace.Entry> lines = entries == null ? Collections.emptyList() : entries;
    Outcome result = outcome == null ? Outcome.IDLE : outcome;
    List<Builder> builders = new ArrayList<>();
    List<LineClass> classes = new ArrayList<>(lines.size());
    Builder current = null;
    int issues = 0, timings = 0;
    Severity worst = Severity.NONE;
    for (int i = 0; i < lines.size(); i++) {
      StartupTrace.Entry entry = lines.get(i);
      if (entry.kind == StartupTrace.Entry.STAGE_START) {
        if (current != null && !current.closed) current.close(entry.atMs, false);
        current = new Builder(entry.body, entry.atMs, i);
        builders.add(current);
        classes.add(LineClass.STAGE);
        continue;
      }
      if (entry.kind == StartupTrace.Entry.STAGE_DONE
          || entry.kind == StartupTrace.Entry.STAGE_LEFT) {
        if (current == null || current.closed) {
          // 日志轮换掉了开始行：补一个从首行开始的隐式阶段。
          current = new Builder(null, lines.get(0).atMs, i);
          builders.add(current);
        }
        if (current.name == null) current.name = entry.body;
        current.close(entry.atMs, entry.kind == StartupTrace.Entry.STAGE_LEFT);
        classes.add(LineClass.STAGE);
        continue;
      }
      if (current == null) {
        current = new Builder(null, entry.atMs, i);
        builders.add(current);
      }
      Item item = item(entry, i, current.startMs, result);
      current.items.add(item);
      if (item.kind == ItemKind.TIMING) timings++;
      if (item.severity != Severity.NONE) {
        issues++;
        if (item.severity == Severity.BAD || worst == Severity.NONE) worst = item.severity;
        if (item.severity == Severity.BAD || current.worst == Severity.NONE)
          current.worst = item.severity;
      }
      classes.add(
          item.severity == Severity.BAD
              ? LineClass.BAD
              : item.severity == Severity.WARN
                  ? LineClass.WARN
                  : item.kind == ItemKind.TIMING ? LineClass.TIMING : LineClass.NOTE);
    }
    List<Stage> stages = new ArrayList<>(builders.size());
    for (int i = 0; i < builders.size(); i++) {
      Builder b = builders.get(i);
      boolean last = i == builders.size() - 1;
      stages.add(b.build(last, result, endMs));
    }
    return new Result(result, stages, issues, worst, timings, lines.size(), classes);
  }

  /** 未关闭阶段的结束时刻：运行中取当前，已就绪取后端 / 浏览器就绪时刻，其余取停止计时。 */
  public static long endMs(StartupTrace.Snapshot snapshot, Outcome outcome) {
    if (snapshot == null) return 0;
    if (outcome == Outcome.READY) {
      if (snapshot.browserReadyMs >= 0) return snapshot.browserReadyMs;
      if (snapshot.backendReadyMs >= 0) return snapshot.backendReadyMs;
    }
    return snapshot.elapsedMs;
  }

  private static Item item(StartupTrace.Entry entry, int index, long stageStart, Outcome outcome) {
    String body = entry.body;
    long offset = Math.max(0, entry.atMs - stageStart);
    if (entry.problem)
      return new Item(
          ItemKind.ISSUE,
          outcome == Outcome.FAILED ? Severity.BAD : Severity.WARN,
          body,
          offset,
          entry.atMs,
          -1,
          index);
    Matcher prep = PREP.matcher(body);
    if (prep.matches()) {
      Matcher result = PREP_OUTCOME.matcher(body);
      boolean threw = result.find() && "threw".equals(result.group(1));
      long ms = parseLong(prep.group(2));
      if (ms >= 0)
        return new Item(
            ItemKind.TIMING,
            threw ? Severity.BAD : Severity.NONE,
            prep.group(1),
            ms,
            entry.atMs,
            -1,
            index);
    }
    Matcher cas = CAS.matcher(body);
    if (cas.matches()) {
      Matcher phase = CAS_PHASE.matcher(cas.group(1));
      Matcher elapsed = CAS_ELAPSED.matcher(cas.group(1));
      if (phase.find() && elapsed.find()) {
        Matcher count = CAS_ENTRIES.matcher(cas.group(1));
        long ms = Math.round(parseDouble(elapsed.group(1)));
        if (ms >= 0)
          return new Item(
              ItemKind.TIMING,
              Severity.NONE,
              "cas " + phase.group(1),
              ms,
              entry.atMs,
              count.find() ? parseLong(count.group(1)) : -1,
              index);
      }
    }
    if (ERROR_HEAD.matcher(body).find() || ERROR_ANYWHERE.matcher(body).find())
      return new Item(ItemKind.ISSUE, Severity.WARN, body, offset, entry.atMs, -1, index);
    String label = body;
    Matcher tagged = TAGGED.matcher(body);
    if (tagged.matches()) {
      String rest = tagged.group(2).trim();
      label = rest.isEmpty() || BARE_DURATION.matcher(rest).matches() ? tagged.group(1) : rest;
    }
    return new Item(ItemKind.NOTE, Severity.NONE, label, offset, entry.atMs, -1, index);
  }

  private static long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static double parseDouble(String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static final class Builder {
    String name;
    final long startMs;
    final int firstIndex;
    final List<Item> items = new ArrayList<>();
    boolean closed, left;
    long closedAt;
    Severity worst = Severity.NONE;

    Builder(String name, long startMs, int firstIndex) {
      this.name = name;
      this.startMs = startMs;
      this.firstIndex = firstIndex;
    }

    void close(long at, boolean leftStage) {
      closed = true;
      left = leftStage;
      closedAt = at;
    }

    Stage build(boolean last, Outcome outcome, long endMs) {
      State issueState =
          worst == Severity.BAD ? State.BAD : worst == Severity.WARN ? State.WARN : State.GOOD;
      if (closed) {
        State state = issueState;
        // 失败时最后一个阶段就是停下来的地方。
        if (last && outcome == Outcome.FAILED) state = State.BAD;
        return new Stage(name, startMs, Math.max(0, closedAt - startMs), state, items, firstIndex);
      }
      long duration = Math.max(0, endMs - startMs);
      switch (outcome) {
        case RUNNING:
          return new Stage(
              name,
              startMs,
              duration,
              worst == Severity.BAD ? State.BAD : State.RUNNING,
              items,
              firstIndex);
        case READY:
          return new Stage(
              name, startMs, duration == 0 ? -1 : duration, issueState, items, firstIndex);
        case FAILED:
          return new Stage(name, startMs, duration, State.BAD, items, firstIndex);
        default:
          return new Stage(
              name,
              startMs,
              duration,
              worst == Severity.NONE ? State.MUTED : issueState,
              items,
              firstIndex);
      }
    }
  }
}
