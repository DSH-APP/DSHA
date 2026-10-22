package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** In-memory, run-bound task facts. A claimed control is never delivered twice. */
public final class AgentTaskState {
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
      boolean dismissed) {}

  public record Control(Key key, String action) {}

  private static final class Row {
    final Key key;
    final long started;
    final String action = UUID.randomUUID().toString();
    String phase, reason = "";
    long seen, sequence, controlAt;
    boolean active = true, queued, claimed, stopping, acknowledged, dismissed;

    Row(Key key, String phase, long now) {
      this.key = key;
      this.phase = phase;
      started = seen = now;
    }

    View view() {
      return new View(key, phase, reason, active, started, seen, action, stopping, dismissed);
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
    if (!matches(key.generation(), key.instance(), source)
        || sequence < 1
        || key.turn() < 1
        || key.session() == null
        || key.session().isEmpty()
        || key.session().length() > 256
        || !List.of("running", "reply", "tool", "waiting", "ended").contains(phase)) return false;
    if (sequence <= sessionSequences.getOrDefault(key.session(), 0L)) return false;
    if (rows.containsKey(key) && !rows.get(key).active) return false;
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
      }
    Row row = rows.get(key);
    if (row == null) {
      if (rows.values().stream().filter(r -> r.active).count() >= 32) return false;
      row = new Row(key, phase, now);
      rows.put(key, row);
    }
    if (sequence <= row.sequence || !row.active) return false;
    row.sequence = sequence;
    sessionSequences.put(key.session(), sequence);
    row.seen = now;
    if (phase.equals("ended")) {
      row.active = false;
      row.queued = false;
      row.phase = "ended";
      row.reason =
          List.of("completed", "aborted", "interrupted", "max-tokens", "blocked", "error")
                  .contains(reason)
              ? reason
              : "unknown";
    } else if (!row.stopping) row.phase = phase;
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
    for (Row row : rows.values())
      if (row.active && row.stopping && !row.acknowledged && now - row.controlAt > 10_000) {
        row.phase = "unknown";
        row.reason = "control-unknown";
        row.queued = false;
      }
    for (Row row : rows.values())
      if (row.active && now - row.seen > 15_000) {
        row.phase = "unknown";
        row.reason = "connection-lost";
        row.queued = false;
      }
    List<View> snapshot = new ArrayList<>(rows.size());
    for (Row row : rows.values()) snapshot.add(row.view());
    return Collections.unmodifiableList(snapshot);
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
