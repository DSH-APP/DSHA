package com.deepseekharness.app.core;

import android.content.Context;
import android.os.SystemClock;
import com.deepseekharness.app.util.StartupTrace;
import com.deepseekharness.app.util.SensitiveData;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** 将实际运行输出和网页错误合并为有界时间线；不根据日志自动修改插件。 */
public final class StartupDiagnostics {
  private final StartupTrace trace = new StartupTrace();
  private final StartupIssueBudget issueBudget = new StartupIssueBudget();
  private final Map<String, String> owners = new LinkedHashMap<>();
  private final java.io.File files;
  private final java.util.function.LongSupplier clock;
  private final Events events;
  private String recentError = "";
  private int errorLines;
  private boolean explicitStartupFailure;
  private final com.deepseekharness.app.util.StartupHistoryStore history;
  private final java.util.concurrent.ScheduledExecutorService historyIo;
  private final com.deepseekharness.app.util.ExecutorQuiescence historyGate =
      new com.deepseekharness.app.util.ExecutorQuiescence();
  private String recordId = "", result = "starting", failure = "";
  private long started, lastSaved;
  private volatile String historyError = "";
  public java.util.function.LongConsumer onHealthy = generation -> {};

  interface Events {
    void record(String stage, String detail);
  }

  public StartupDiagnostics(Context context) {
    this(
        context.getApplicationContext().getFilesDir(),
        new com.deepseekharness.app.backup.AndroidBackupFileSystem(),
        SystemClock::elapsedRealtime,
        androidEvents(context),
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
  }

  StartupDiagnostics(
      java.io.File files,
      com.deepseekharness.app.backup.BackupFileSystem filesystem,
      java.util.function.LongSupplier clock,
      Events events,
      java.util.concurrent.ScheduledExecutorService historyIo) {
    this.files = java.util.Objects.requireNonNull(files);
    this.clock = java.util.Objects.requireNonNull(clock);
    this.events = java.util.Objects.requireNonNull(events);
    this.historyIo = java.util.Objects.requireNonNull(historyIo);
    history = new com.deepseekharness.app.util.StartupHistoryStore(filesystem, files);
  }

  private static Events androidEvents(Context context) {
    Context application = context.getApplicationContext();
    return (stage, detail) -> DiagnosticLog.record(application, stage, detail);
  }

  private long now() {
    return clock.getAsLong();
  }

  public synchronized void begin(long generation, boolean safe) {
    persistHistory(true);
    owners.clear();
    recentError = "";
    errorLines = 0;
    explicitStartupFailure = false;
    issueBudget.begin(generation);
    trace.begin(generation, now(), safe);
    recordId = java.util.UUID.randomUUID().toString();
    started = System.currentTimeMillis();
    result = "starting";
    failure = "";
    persistHistory(true);
  }

  public StartupTrace.Snapshot snapshot() {
    return trace.snapshot(now());
  }

  public void message(long generation, String text) {
    trace.owned(generation, now(), text);
  }

  public void stage(long generation, String stage) {
    trace.stage(generation, now(), stage);
    events.record("STARTUP_STAGE", stage);
    persistHistory(false);
  }

  public synchronized void output(long generation, String chunk) {
    if (!trace.isCurrent(generation) || chunk == null) return;
    for (String line : chunk.split("\n")) {
      if (line.startsWith("[DSHA_STARTUP] ")) {
        try {
          Map<String, Object> event =
              com.deepseekharness.app.backup.BackupJson.read(
                  line.substring(15).getBytes(java.nio.charset.StandardCharsets.UTF_8), 64 * 1024);
          if (event.containsKey("fatal") && !(event.get("fatal") instanceof Boolean))
            throw new java.io.IOException("STARTUP_EVENT_FORMAT");
          String type = text(event, "type"),
              name = text(event, "plugin"),
              message = text(event, "message");
          if (name.length() > 214) name = "";
          String path = text(event, "path");
          if (!name.isEmpty() && path.startsWith("/") && owners.size() < 3000)
            owners.put(path + "/", name);
          if (!name.isEmpty() && owners.size() < 3000) owners.put("\"" + name + "\"", name);
          Object values = event.get("ids");
          if (values instanceof java.util.List) {
            java.util.List<?> ids = (java.util.List<?>) values;
            for (int i = 0; i < ids.size() && i < 100 && owners.size() < 3000; i++) {
              String id = ids.get(i) instanceof String ? (String) ids.get(i) : "";
              String key = "\"" + id + "\"";
              if (!id.isEmpty())
                owners.put(
                    key, owners.containsKey(key) && !name.equals(owners.get(key)) ? "" : name);
            }
          }
          if ("stage".equals(type)) stage(generation, message);
          else if ("issue".equals(type)) {
            issue(generation, name, message);
            explicitStartupFailure |= !Boolean.FALSE.equals(event.get("fatal"));
          } else if (!message.isEmpty()) trace.owned(generation, now(), message);
          continue;
        } catch (Exception ignored) {
        }
      }
      trace.add(generation, now(), line);
      if (line.matches("(?i).*(error|failed|cannot|did not activate|missing).*")) {
        recentError = line;
        errorLines = 12;
        String name = owner(line);
        if (!name.isEmpty()) related(generation, name, line);
      } else if (errorLines-- > 0) {
        String name = owner(line);
        if (!name.isEmpty()) related(generation, name, recentError + "\n" + line);
      }
    }
    persistHistory(false);
  }

  private static String text(Map<String, Object> event, String key) {
    Object value = event.get(key);
    return value instanceof String ? (String) value : "";
  }

  private String owner(String text) {
    return com.deepseekharness.app.util.PluginFailureOwner.find(owners, text);
  }

  public synchronized boolean hasExplicitStartupFailure(long generation) {
    return trace.isCurrent(generation) && explicitStartupFailure && !snapshot().browserReady;
  }

  public synchronized void browser(long generation, String detail) {
    if (!trace.isCurrent(generation)) return;
    // 控制台 ERROR 不是启动故障证据；浏览器干预和运行错误仍完整保留在时间线。
    String category =
        com.deepseekharness.app.util.BrowserDiagnostic.category(
            detail, snapshot().browserReady, false);
    trace.add(generation, now(), category + ": " + detail);
    events.record(category, SensitiveData.redact(detail));
    persistHistory(false);
  }

  public synchronized boolean pageEvent(long generation, JSONObject event) {
    if (!trace.isCurrent(generation)) return false;
    String type = event.optString("type"), detail = event.optString("message");
    String id = event.optString("id");
    boolean alreadyReady = snapshot().browserReady;
    // WebPreview 关闭后会销毁页面；再次进入时同一批已成功插件会重新发 loading/active/ready。
    // 首次 ready 后这些成功事件不再增加诊断日志或触发磁盘历史写入，真实错误仍继续记录。
    if ("ready".equals(type)) {
      if (!alreadyReady) browserReady(generation);
      return false;
    }
    if ("issue".equals(type)) {
      String name = owner("\"" + id + "\" " + detail);
      String message =
          (id.isEmpty() ? "" : id + com.deepseekharness.app.util.UiText.text("：")) + detail;
      if (alreadyReady || !event.optBoolean("fatal")) {
        browser(generation, message);
        return false;
      }
      issue(generation, name, message);
      if (name.isEmpty())
        for (String line : detail.split("\n")) {
          String candidate = owner(line);
          if (!candidate.isEmpty()) issue(generation, candidate, line);
        }
      return event.optBoolean("fatal") && !alreadyReady;
    }
    if (!alreadyReady) trace.owned(generation, now(), detail);
    return false;
  }

  /** A keyword near a plugin path is related evidence, not a confirmed loading failure. */
  private void related(long generation, String name, String detail) {
    trace.add(generation, now(), "RELATED_PLUGIN_LOG[" + name + "]: " + detail);
  }

  public synchronized void issue(long generation, String name, String detail) {
    if (!trace.isCurrent(generation)) return;
    trace.issue(generation, now(), name, detail);
    if (issueBudget.record(generation, name, detail))
      events.record(
          snapshot().browserReady ? "RUNTIME_ERROR" : "STARTUP_ERROR",
          name + ": " + SensitiveData.redact(detail));
  }

  public synchronized void browserReady(long generation) {
    if (!trace.isCurrent(generation)) return;
    boolean capture = !trace.snapshot(now()).browserReady;
    trace.browserReady(generation, now());
    result = "ready";
    persistHistory(true);
    if (capture && !snapshot().safe && snapshot().issues.isEmpty())
      historyGate.execute(historyIo, () -> onHealthy.accept(generation));
  }

  public synchronized void completed(long generation, String status, String reason) {
    if (!trace.isCurrent(generation)) return;
    if ("failed".equals(result) && "stopped".equals(status)) return;
    result = status;
    failure = reason == null ? "" : reason;
    persistHistory(true);
  }

  public synchronized String recordId() {
    return recordId;
  }

  public java.util.List<com.deepseekharness.app.util.StartupHistoryStore.Entry> history() {
    return history.list(com.deepseekharness.app.util.UiText.language());
  }

  public String failureReason() {
    for (String detail : snapshot().issues.values()) {
      int port = com.deepseekharness.app.util.WebPortPolicy.conflictPort(detail);
      if (port > 0) return com.deepseekharness.app.util.UiText.format("Web 端口被占用：%s", port);
    }
    return com.deepseekharness.app.util.UiText.choose(
        "启动配置或插件加载失败", "Startup configuration or plugin loading failed");
  }

  public String historyError() {
    return historyError;
  }

  private synchronized void persistHistory(boolean force) {
    if (recordId.isEmpty()) return;
    long now = now();
    if (!force && now - lastSaved < 1000) return;
    lastSaved = now;
    String id = recordId, status = result, reason = failure;
    long date = started;
    StartupTrace.Snapshot zh = trace.snapshot(now, "zh"), en = trace.snapshot(now, "en");
    historyGate.execute(
        historyIo,
        () -> {
          try {
            history.save(id, date, status, reason, zh, en);
            historyError = "";
          } catch (Exception error) {
            historyError = SensitiveData.redact(String.valueOf(error.getMessage()));
            events.record("STARTUP_HISTORY", historyError);
          }
        });
  }

  /** 格式化删 filesDir 前阻止新历史写入，并等待此前已接受的保存/健康快照全部返回。 */
  public void drainForFactoryReset(long timeoutMillis) throws java.io.IOException {
    historyGate.closeAndAwait(historyIo, timeoutMillis);
  }

  /** 清除磁盘后不能让下一次 begin 把格式化前的内存记录重新写回。 */
  public synchronized void completeFactoryResetDrain() {
    owners.clear();
    recentError = "";
    errorLines = 0;
    explicitStartupFailure = false;
    issueBudget.begin(0);
    recordId = "";
    result = "starting";
    failure = "";
    started = 0;
    lastSaved = 0;
    historyError = "";
    historyGate.reopen();
  }

  /** 成功重试不得覆盖上次失败证据，供旧设备无输出退出时排查。 */
  public void preserveFailure(java.io.File runtimeLog, String reason) {
    try {
      String text =
          com.deepseekharness.app.util.UiText.format(
              "最近启动失败\n%s\n%s\n\n%s\n\n=== 进程输出 ===\n",
              new java.util.Date(), reason, snapshot().log);
      if (runtimeLog.isFile() && !com.deepseekharness.app.util.Compat.isSymbolicLink(runtimeLog))
        text += com.deepseekharness.app.util.TextLogTail.read(runtimeLog, 256 * 1024);
      com.deepseekharness.app.util.Compat.write(
          new java.io.File(files, "last-startup-failure.log"),
          SensitiveData.redact(text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception ignored) {
    }
  }

  public static String lastFailure(Context context) {
    try {
      java.io.File file = new java.io.File(context.getFilesDir(), "last-startup-failure.log");
      return file.isFile()
          ? SensitiveData.redact(com.deepseekharness.app.util.TextLogTail.read(file, 384 * 1024))
          : com.deepseekharness.app.util.UiText.text("暂无保留的失败记录\n");
    } catch (Exception error) {
      return com.deepseekharness.app.util.UiText.text("失败记录暂不可读\n");
    }
  }
}
