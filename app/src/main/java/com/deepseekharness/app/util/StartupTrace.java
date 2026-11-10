package com.deepseekharness.app.util;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** 有界、脱敏的启动时间线；阶段和插件故障随任务保留，不依赖某个页面的回调。 */
public final class StartupTrace {
  private static final int MAX_CHARS = 48 * 1024;
  private static final int MAX_LINES = 300;
  private final ArrayDeque<Line> lines = new ArrayDeque<>();
  private final LinkedHashMap<String, String> issues = new LinkedHashMap<>();
  private long generation, began, stageBegan, revision;
  private long backendReadyAt = -1, browserReadyAt = -1, endedAt = -1;
  private String stage = com.deepseekharness.app.util.UiText.text("尚未启动");
  private int length;

  private static final class Line {
    final long at, runtimeAt;
    final String text, previous;
    final int kind;
    final boolean problem;

    Line(long at, long runtimeAt, String text, String previous, int kind, boolean problem) {
      this.at = at;
      this.runtimeAt = runtimeAt;
      this.text = text;
      this.previous = previous;
      this.kind = kind;
      this.problem = problem;
    }

    /** 不含时间前缀的显示正文；阶段行只返回阶段名，供启动页概览按 kind 分组。 */
    String body(String language) {
      return kind == 0 ? text : StartupText.render(text, language);
    }

    String render(String language) {
      String value = body(language);
      if (kind == 2) value = StartupText.choose(language, "正在", "Current stage: ") + value + "…";
      if (kind == 3)
        value =
            StartupText.choose(language, "完成「", "Completed “")
                + value
                + StartupText.choose(language, "」，耗时 ", "” in ")
                + previous;
      if (kind == 4)
        value =
            StartupText.choose(language, "切换离开「", "Left stage “")
                + value
                + StartupText.choose(language, "」，停留 ", "” after ")
                + previous;
      return runtimeAt < 0
          ? "[" + format(at) + "] " + value
          : "["
              + StartupText.choose(language, "运行 +", "Runtime +")
              + format(runtimeAt)
              + StartupText.choose(language, " / 自启动记录 ", " / since startup record ")
              + format(at)
              + "] "
              + value;
    }
  }

  private boolean safe, browserReady;
  // 启动页会周期性刷新；同一 revision/language 下日志文本不变，避免重复遍历 300 行。
  private long renderedRevision = -1;
  private String renderedLanguage = "";
  private String renderedLog = "";
  private java.util.List<Entry> renderedEntries = java.util.Collections.emptyList();
  // issue() 产生的标题与原因行；用于概览直接上浮，不靠匹配中文文案。
  private boolean appendingProblem;

  /** 结构化日志行：与 {@link Snapshot#log} 一一对应，复制 / 导出仍只用完整原文。 */
  public static final class Entry {
    /** 普通输出 / DSHA 自有输出 / 阶段开始 / 阶段完成 / 阶段切离（等待鉴权时离开）。 */
    public static final int OUTPUT = 0, OWNED = 1, STAGE_START = 2, STAGE_DONE = 3, STAGE_LEFT = 4;

    /** 自本次启动开始的毫秒数。 */
    public final long atMs;

    /** 网页就绪后的运行毫秒数；就绪前为 -1。 */
    public final long runtimeMs;

    public final int kind;

    /** 阶段行是阶段名，其余是去掉时间前缀的正文。 */
    public final String body;

    /** 与 {@link Snapshot#log} 中同一行完全相同的原文（含时间前缀）。 */
    public final String line;

    /** 由 issue() 记录的插件 / 端口 / 启动异常。 */
    public final boolean problem;

    public Entry(long atMs, long runtimeMs, int kind, String body, String line, boolean problem) {
      this.atMs = atMs;
      this.runtimeMs = runtimeMs;
      this.kind = kind;
      this.body = body == null ? "" : body;
      this.line = line == null ? "" : line;
      this.problem = problem;
    }
  }

  public static final class Snapshot {
    public final long generation, revision, elapsedMs, stageElapsedMs, runElapsedMs;

    /** -1 means the corresponding milestone has not been observed in this generation. */
    public final long backendReadyMs, browserReadyMs;

    public final String stage, log;
    public final boolean safe, browserReady, backendReady, ended;
    public final Map<String, String> issues;

    /** 与 log 同序的结构化行；不可修改。 */
    public final java.util.List<Entry> entries;

    Snapshot(
        long generation,
        long revision,
        long elapsed,
        long stageElapsed,
        long runElapsed,
        long backendElapsed,
        long browserElapsed,
        String stage,
        String log,
        boolean safe,
        boolean ready,
        boolean ended,
        Map<String, String> issues,
        java.util.List<Entry> entries) {
      this.generation = generation;
      this.revision = revision;
      elapsedMs = elapsed;
      stageElapsedMs = stageElapsed;
      runElapsedMs = runElapsed;
      backendReadyMs = backendElapsed;
      browserReadyMs = browserElapsed;
      backendReady = backendElapsed >= 0;
      this.stage = stage;
      this.log = log;
      this.safe = safe;
      browserReady = ready;
      this.ended = ended;
      this.issues = java.util.Collections.unmodifiableMap(issues);
      this.entries = entries;
    }
  }

  public synchronized void begin(long next, long now, boolean safeMode) {
    generation = next;
    began = stageBegan = now;
    stage = com.deepseekharness.app.util.UiText.text("排队启动");
    safe = safeMode;
    browserReady = false;
    backendReadyAt = browserReadyAt = endedAt = -1;
    lines.clear();
    issues.clear();
    length = 0;
    revision++;
    renderedRevision = -1;
    renderedLanguage = "";
    renderedLog = "";
    renderedEntries = java.util.Collections.emptyList();
    owned(
        next,
        now,
        safeMode
            ? com.deepseekharness.app.util.UiText.text("开始安全启动：使用独立基础配置，原插件和配置保留")
            : com.deepseekharness.app.util.UiText.text("开始启动 DSH"));
  }

  public synchronized void stage(long expected, long now, String next) {
    if (expected != generation || next == null || next.equals(stage)) return;
    if (browserReady) {
      add(expected, now, "RUNTIME_STAGE: " + next);
      return;
    }
    // Observer phases can replace this display stage before the official auth line arrives.
    // A display-stage transition is not evidence that authentication completed.
    boolean awaitingAuthentication =
        "等待鉴权链接".equals(StartupText.render(stage, "zh")) && backendReadyAt < 0;
    append(now, stage, format(now - stageBegan), awaitingAuthentication ? 4 : 3);
    stage = clean(next);
    stageBegan = now;
    append(now, stage, "", 2);
  }

  public synchronized void add(long expected, long now, String text) {
    add(expected, now, text, false);
  }

  public synchronized void owned(long expected, long now, String text) {
    add(expected, now, text, true);
  }

  private void add(long expected, long now, String text, boolean owned) {
    if (expected != generation || text == null) return;
    for (String raw : text.replace('\r', '\n').split("\n")) {
      String value = clean(raw);
      if (value.isEmpty()) continue;
      if (value.length() > 3000) value = value.substring(0, 3000) + "…";
      append(now, value, "", owned ? 1 : 0);
    }
  }

  private void append(long now, String text, String previous, int kind) {
    Line line =
        new Line(
            now - began,
            browserReady ? Math.max(0, now - browserReadyAt) : -1,
            text,
            previous,
            kind,
            appendingProblem);
    if (!lines.isEmpty() && lines.peekLast().render("zh").equals(line.render("zh"))) return;
    lines.addLast(line);
    length += line.text.length() + line.previous.length() + 80;
    while (length > MAX_CHARS || lines.size() > MAX_LINES) {
      Line old = lines.removeFirst();
      length -= old.text.length() + old.previous.length() + 80;
    }
    revision++;
  }

  public synchronized void issue(long expected, long now, String plugin, String detail) {
    if (expected != generation) return;
    String name = plugin == null ? "" : clean(plugin), reason = clean(detail);
    int conflict = WebPortPolicy.conflictPort(reason);
    if (conflict > 0) name = "";
    if (name.length() > 180 || reason.isEmpty()) return;
    if (browserReady) {
      appendingProblem = true;
      try {
        add(
            expected,
            now,
            "RUNTIME_ERROR" + (name.isEmpty() ? "" : "[" + name + "]") + ": " + reason);
      } finally {
        appendingProblem = false;
      }
      return;
    }
    if (reason.length() > 1200) reason = reason.substring(0, 1200) + "…";
    if (reason.equals(issues.get(name))) return;
    issues.put(name, reason);
    while (issues.size() > 20) issues.remove(issues.keySet().iterator().next());
    appendingProblem = true;
    try {
      owned(
          expected,
          now,
          conflict > 0 ? "Web 端口被占用：" + conflict : name.isEmpty() ? "DSH 启动异常" : "插件加载异常：" + name);
      add(expected, now, reason);
    } finally {
      appendingProblem = false;
    }
  }

  public synchronized void backendReady(long expected, long now) {
    if (expected != generation || backendReadyAt >= 0) return;
    backendReadyAt = now;
    add(expected, now, "BACKEND_AUTH_READY: " + format(now - began));
    revision++;
  }

  public synchronized void browserReady(long expected, long now) {
    if (expected != generation || browserReady) return;
    stage(expected, now, com.deepseekharness.app.util.UiText.text("网页已就绪"));
    add(expected, now, "BROWSER_READY: " + format(now - began));
    browserReadyAt = now;
    browserReady = true;
    revision++;
  }

  /** Stop startup duration without losing subsequent process output or its elapsed timestamps. */
  public synchronized void completed(long expected, long now) {
    if (expected != generation || endedAt >= 0) return;
    endedAt = now;
    revision++;
  }

  public synchronized Snapshot snapshot(long now) {
    return snapshot(now, UiText.language());
  }

  public synchronized Snapshot snapshot(long now, String language) {
    String keyLanguage = language == null ? "" : language;
    if (renderedRevision != revision || !renderedLanguage.equals(keyLanguage)) {
      renderedLog = renderLog(keyLanguage);
      renderedEntries = renderEntries(keyLanguage);
      renderedRevision = revision;
      renderedLanguage = keyLanguage;
    }
    return new Snapshot(
        generation,
        revision,
        Math.max(0, (browserReady ? browserReadyAt : endedAt >= 0 ? endedAt : now) - began),
        Math.max(0, (browserReady ? browserReadyAt : endedAt >= 0 ? endedAt : now) - stageBegan),
        Math.max(0, (endedAt >= 0 ? endedAt : now) - began),
        backendReadyAt < 0 ? -1 : Math.max(0, backendReadyAt - began),
        browserReadyAt < 0 ? -1 : Math.max(0, browserReadyAt - began),
        StartupText.render(stage, language),
        renderedLog,
        safe,
        browserReady,
        endedAt >= 0,
        new LinkedHashMap<>(issues),
        renderedEntries);
  }

  private java.util.List<Entry> renderEntries(String language) {
    java.util.ArrayList<Entry> result = new java.util.ArrayList<>(lines.size());
    for (Line line : lines)
      result.add(
          new Entry(
              line.at,
              line.runtimeAt,
              line.kind,
              line.body(language),
              line.render(language),
              line.problem));
    return java.util.Collections.unmodifiableList(result);
  }

  private String renderLog(String language) {
    StringBuilder result = new StringBuilder();
    for (Line line : lines) {
      if (result.length() > 0) result.append('\n');
      result.append(line.render(language));
    }
    return result.toString();
  }

  public synchronized boolean isCurrent(long expected) {
    return expected > 0 && expected == generation;
  }

  private static String format(long ms) {
    return String.format(java.util.Locale.ROOT, "%.1fs", Math.max(0, ms) / 1000.0);
  }

  private static String clean(String text) {
    return SensitiveData.redact(
            text == null ? "" : text.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", ""))
        .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
        .trim();
  }
}
