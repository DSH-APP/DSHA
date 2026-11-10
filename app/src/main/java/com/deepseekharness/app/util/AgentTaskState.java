package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** In-memory, run-bound task facts. A claimed control is never delivered twice. */
public final class AgentTaskState {
  /** 事件静默超过此值：不再宣称任务正在执行，先显示“状态未知”。 */
  public static final long STALE_UNKNOWN_MS = 15_000;

  /** 事件静默超过此值：按连接中断结束，避免胶囊永远停在“执行中”。 */
  public static final long STALE_END_MS = 60_000;

  private static final int DETAIL_LIMIT = 64;

  private static final int TITLE_LIMIT = 48;

  private static final List<String> PHASES =
      List.of("running", "reply", "tool", "waiting", "ended");

  private static final List<String> END_REASONS =
      List.of("completed", "aborted", "interrupted", "max-tokens", "blocked", "error");

  public record Key(long generation, String instance, String session, long turn, String owner) {
    public Key(long generation, String instance, String session, long turn) {
      this(generation, instance, session, turn, "");
    }
  }

  public record View(
      Key key,
      String phase,
      String reason,
      boolean active,
      long started,
      long seen,
      String action,
      boolean stopping,
      boolean dismissed,
      String detail,
      String title,
      long phaseAt,
      String question) {
    /** 兼容没有问题的调用方（详情/标题已有，问题为空）。 */
    public View(
        Key key,
        String phase,
        String reason,
        boolean active,
        long started,
        long seen,
        String action,
        boolean stopping,
        boolean dismissed,
        String detail,
        String title,
        long phaseAt) {
      this(
          key, phase, reason, active, started, seen, action, stopping, dismissed, detail, title,
          phaseAt, "");
    }

    /** 兼容旧构造：没有详情的老调用方按空详情处理。 */
    public View(
        Key key,
        String phase,
        String reason,
        boolean active,
        long started,
        long seen,
        String action,
        boolean stopping,
        boolean dismissed) {
      this(
          key, phase, reason, active, started, seen, action, stopping, dismissed, "", "", started,
          "");
    }
  }

  public record Control(Key key, String action) {}

  private static final class Row {
    final Key key;
    final long started;
    final String action = UUID.randomUUID().toString();
    String phase, reason = "", detail = "", title = "", question = "";
    long seen, sequence, controlAt, phaseAt;
    boolean active = true, queued, claimed, stopping, acknowledged, dismissed;

    Row(Key key, String phase, long now) {
      this.key = key;
      this.phase = phase;
      started = seen = phaseAt = now;
    }

    View view() {
      return new View(
          key, phase, reason, active, started, seen, action, stopping, dismissed, detail, title,
          phaseAt, question);
    }
  }

  private final LinkedHashMap<Key, Row> rows = new LinkedHashMap<>();
  private final java.util.Map<String, Long> sessionSequences = new java.util.HashMap<>();
  private long generation = -1;
  private String instance = "", client = "";

  public synchronized void identity(long next, String id) {
    if (next == generation && id.equals(instance)) return;
    generation = next;
    instance = id;
    client = "";
    sessionSequences.clear();
    for (Row row : rows.values()) {
      row.active = false;
      row.queued = false;
      row.phase = "unknown";
      row.reason = "runtime-changed";
      row.question = "";
    }
    prune();
  }

  /** 暂失运行证明时终结旧票；同代重新登记也不能恢复已经排队或失效的row。 */
  public synchronized void invalidate(long expected, String id) {
    if (generation == expected && instance.equals(id)) identity(-1, "");
  }

  public synchronized boolean bind(String source) {
    if (!source.matches("[a-f0-9]{32}")) return false;
    if (client.isEmpty()) client = source;
    return client.equals(source);
  }

  public synchronized boolean bind(String source, long now) {
    if (!source.matches("[a-f0-9]{32}")) return false;
    if (!client.isEmpty() && !client.equals(source)) {
      if (rows.values().stream().anyMatch(row -> row.active && now - row.seen <= 15_000))
        return false;
      for (Row row : rows.values())
        if (row.active) {
          row.active = false;
          row.queued = false;
          row.phase = "unknown";
          row.reason = "reporter-changed";
          row.question = "";
        }
      client = source;
      sessionSequences.clear();
      prune();
      return true;
    }
    return bind(source);
  }

  public synchronized boolean matches(long gen, String id, String source) {
    return gen == generation && instance.equals(id) && !client.isEmpty() && client.equals(source);
  }

  public synchronized boolean event(
      String source, Key key, long sequence, String phase, String reason, long now) {
    return event(source, key, sequence, phase, reason, "", "", "", now);
  }

  public synchronized boolean event(
      String source,
      Key key,
      long sequence,
      String phase,
      String reason,
      String detail,
      String title,
      long now) {
    return event(source, key, sequence, phase, reason, detail, title, "", now);
  }

  /**
   * 记录一条任务事实。
   *
   * <p>{@code detail}/{@code title} 是给展开通知与详情页的补充信息，来自不可信的上报端，
   * 这里再次限长并压成单行。真实终态（completed/aborted/...）不会因为后续事件复活，
   * 但“连接中断”与“上报端切换”造成的未知允许同一轮的新事件恢复，避免一次瞬时断连让胶囊永久卡住。
   */
  public synchronized boolean event(
      String source,
      Key key,
      long sequence,
      String phase,
      String reason,
      String detail,
      String title,
      String question,
      long now) {
    if (!matches(key.generation(), key.instance(), source)
        || sequence < 1
        || key.turn() < 1
        || key.session() == null
        || key.session().isEmpty()
        || key.session().length() > 256
        || !PHASES.contains(phase)) return false;
    if (sequence <= sessionSequences.getOrDefault(key.session(), 0L)) return false;
    for (Row old : rows.values())
      if (old.key.generation() == key.generation()
          && old.key.instance().equals(key.instance())
          && old.key.session().equals(key.session())
          && old.key.owner().equals(key.owner())
          && old.key.turn() > key.turn()) return false;
    for (Row old : rows.values())
      if (old.active
          && old.key.generation() == key.generation()
          && old.key.instance().equals(key.instance())
          && old.key.session().equals(key.session())
          && (old.key.turn() < key.turn() || !old.key.owner().equals(key.owner()))) {
        old.active = false;
        old.queued = false;
        old.phase = "unknown";
        old.reason = "new-turn-observed";
        old.detail = "";
        old.question = "";
      }
    Row row = rows.get(key);
    if (row == null) {
      if (rows.values().stream().filter(r -> r.active).count() >= 32) return false;
      row = new Row(key, phase, now);
      rows.put(key, row);
    } else {
      if (sequence <= row.sequence) return false;
      if (!row.active) {
        if (!revivable(row)) return false;
        row.active = true;
        row.queued = false;
        row.stopping = false;
        row.acknowledged = false;
        row.reason = "";
        row.phaseAt = now;
      }
    }
    row.sequence = sequence;
    sessionSequences.put(key.session(), sequence);
    row.seen = now;
    String nextDetail = sanitize(detail, DETAIL_LIMIT);
    String nextTitle = sanitize(title, TITLE_LIMIT);
    if (!phase.equals(row.phase) || !nextDetail.equals(row.detail)) row.phaseAt = now;
    row.detail = nextDetail;
    row.title = nextTitle;
    row.question = sanitize(question, 2000);
    if (phase.equals("ended")) {
      row.active = false;
      row.queued = false;
      row.phase = "ended";
      row.reason = END_REASONS.contains(reason) ? reason : "unknown";
      // 终态不再保留工具路径/问题，避免“读取 xxx”或待答选项残留在完成卡片上。
      row.detail = "";
      row.question = "";
    } else if (!row.stopping) {
      row.phase = phase;
      row.reason = reason;
    }
    prune();
    return true;
  }

  public synchronized boolean requestStop(Key key, String action, long now) {
    Row row = rows.get(key);
    if (row == null
        || !row.active
        || row.stopping
        || key.generation() != generation
        || !instance.equals(key.instance())
        || !row.action.equals(action)
        || now - row.seen > 15_000) return false;
    row.stopping = true;
    row.queued = true;
    row.phase = "stop-requested";
    row.controlAt = now;
    return true;
  }

  public synchronized boolean dismiss(Key key, String action) {
    Row row = rows.get(key);
    if (row == null || !row.action.equals(action)) return false;
    row.dismissed = true;
    return true;
  }

  public synchronized Control claim(String source, long gen, String id) {
    if (!matches(gen, id, source)) return null;
    for (Row row : rows.values())
      if (row.active && row.queued && !row.claimed) {
        row.queued = false;
        row.claimed = true;
        return new Control(row.key, row.action);
      }
    return null;
  }

  public synchronized boolean ack(String source, Key key, String action, String outcome, long now) {
    Row row = rows.get(key);
    if (!matches(key.generation(), key.instance(), source)
        || row == null
        || !row.claimed
        || row.acknowledged
        || !row.active
        || !row.action.equals(action)) return false;
    row.acknowledged = true;
    row.phase = outcome.equals("requested") ? "stop-requested" : "unknown";
    row.reason = outcome.equals("requested") ? "" : "control-unknown";
    row.seen = now;
    return true;
  }

  public synchronized List<View> snapshot(long now) {
    for (Row row : rows.values()) {
      if (!row.active) continue;
      if (row.stopping && !row.acknowledged && now - row.controlAt > 10_000) {
        row.phase = "unknown";
        row.reason = "control-unknown";
        row.queued = false;
      }
      long silence = now - row.seen;
      if (silence > STALE_END_MS) {
        // 心跳彻底中断：按连接中断结束，不能继续占着 ongoing 通知与岛位。
        row.active = false;
        row.queued = false;
        row.phase = "ended";
        row.reason = "connection-lost";
        row.detail = "";
        row.question = "";
        row.phaseAt = now;
      } else if (silence > STALE_UNKNOWN_MS && row.reason.isEmpty()) {
        row.phase = "unknown";
        row.reason = "connection-lost";
        row.queued = false;
      }
    }
    List<View> snapshot = new ArrayList<>(rows.size());
    for (Row row : rows.values()) snapshot.add(row.view());
    return Collections.unmodifiableList(snapshot);
  }

  /** 恢复条件只覆盖“瞬时断连”与“上报端切换”；真实终态与停止结果未知不会复活。 */
  private static boolean revivable(Row row) {
    return !row.active
        && ("connection-lost".equals(row.reason) || "reporter-changed".equals(row.reason));
  }

  private static String sanitize(String value, int limit) {
    if (value == null) return "";
    String one = value.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
    if (one.isEmpty()) return "";
    if (one.codePointCount(0, one.length()) <= limit) return one;
    return one.substring(0, one.offsetByCodePoints(0, limit));
  }

  private void prune() {
    var ended = new ArrayList<Key>();
    for (Row row : rows.values()) if (!row.active) ended.add(row.key);
    for (int i = 0; i < ended.size() - 5; i++) {
      Key removed = ended.get(i);
      rows.remove(removed);
      if (rows.keySet().stream().noneMatch(key -> key.session().equals(removed.session())))
        sessionSequences.remove(removed.session());
    }
  }
}
