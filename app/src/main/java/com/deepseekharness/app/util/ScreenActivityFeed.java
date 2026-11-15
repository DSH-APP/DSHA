package com.deepseekharness.app.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 实体主屏活动的内存环形缓冲：约 120 秒 / 64 条，不落盘、不进备份或诊断日志。
 *
 * <p>记录只在手机任务活动期间发生；任务结束、暂停或无障碍断开时 {@link #clear()}。每个任务有自己的
 * 身份与回执游标：{@link #recent(int, String)} 只返回该任务上次回执之后的事件（最多 {@code max} 条，
 * 取最近的几条），{@link #history(long, int, String)} 按序号增量分页。身份不匹配时不返回任何事件，
 * 也不推进游标。
 */
public final class ScreenActivityFeed {
  public static final int DEFAULT_CAPACITY = 64;
  public static final long DEFAULT_WINDOW_MILLIS = 120_000L;

  private final LongSupplier clock;
  private final int capacity;
  private final long windowMillis;
  private final ArrayDeque<ScreenActivityEvent> events = new ArrayDeque<>();
  private String taskIdentity;
  private boolean recording;
  private long startedAtMillis;
  private long deliveredThrough;
  private long latestSequence;

  public ScreenActivityFeed(LongSupplier clock) {
    this(clock, DEFAULT_CAPACITY, DEFAULT_WINDOW_MILLIS);
  }

  public ScreenActivityFeed(LongSupplier clock, int capacity, long windowMillis) {
    this.clock = Objects.requireNonNull(clock, "SCREEN_ACTIVITY_CLOCK_MISSING");
    if (capacity <= 0) throw new IllegalArgumentException("SCREEN_ACTIVITY_CAPACITY_INVALID");
    if (windowMillis <= 0) throw new IllegalArgumentException("SCREEN_ACTIVITY_WINDOW_INVALID");
    this.capacity = capacity;
    this.windowMillis = windowMillis;
  }

  /**
   * 开始或继续保持一个任务的活动记录。同一身份重复调用不重置缓冲；身份变化则先清空旧任务事件。
   *
   * @return true 表示这次调用新开了记录（身份变化或此前未记录），调用方可以借此刷新排除列表
   */
  public synchronized boolean begin(String identity) {
    if (identity == null || identity.isEmpty()) return false;
    if (recording && identity.equals(taskIdentity)) return false;
    events.clear();
    recording = true;
    taskIdentity = identity;
    startedAtMillis = clock.getAsLong();
    deliveredThrough = latestSequence;
    return true;
  }

  /** 任务结束、暂停或服务断开：立即停止记录并清空全部事件。 */
  public synchronized void clear() {
    events.clear();
    recording = false;
    taskIdentity = null;
    startedAtMillis = 0L;
    deliveredThrough = latestSequence;
  }

  public synchronized boolean recording() {
    return recording;
  }

  public synchronized String taskIdentity() {
    return taskIdentity;
  }

  public synchronized long startedAtMillis() {
    return recording ? startedAtMillis : 0L;
  }

  public synchronized long latestSequence() {
    return latestSequence;
  }

  /** 记录一条事件；未在记录时返回 null。序号与时间由缓冲统一盖章。 */
  public synchronized ScreenActivityEvent record(ScreenActivityEvent draft) {
    if (!recording || draft == null) return null;
    long now = clock.getAsLong();
    prune(now);
    ScreenActivityEvent event = draft.stamped(++latestSequence, now);
    events.addLast(event);
    while (events.size() > capacity) events.removeFirst();
    return event;
  }

  /**
   * 上次回执之后的最近事件（回执自动附带用）。取最新的至多 {@code max} 条，按时间从旧到新返回；
   * 游标推进到本批最新一条。任务身份不匹配或未在记录时返回空表且不推进游标。
   */
  public synchronized List<ScreenActivityEvent> recent(int max, String identity) {
    if (!matches(identity) || max <= 0) return List.of();
    prune(clock.getAsLong());
    List<ScreenActivityEvent> pending = new ArrayList<>();
    for (ScreenActivityEvent event : events) {
      if (event.sequence() > deliveredThrough) pending.add(event);
    }
    if (pending.isEmpty()) return List.of();
    int from = Math.max(0, pending.size() - max);
    List<ScreenActivityEvent> page = new ArrayList<>(pending.subList(from, pending.size()));
    deliveredThrough = page.get(page.size() - 1).sequence();
    return List.copyOf(page);
  }

  /**
   * 按序号增量读取（显式 history 查询用）：返回 {@code since} 之后最早的一页，最多 {@code limit} 条，
   * 按时间从旧到新。{@code nextSince} 是本页最后一条的序号；{@code truncated} 表示后面还有更多。
   */
  public synchronized History history(long since, int limit, String identity) {
    if (!matches(identity)) return new History(false, List.of(), since, false);
    prune(clock.getAsLong());
    List<ScreenActivityEvent> page = new ArrayList<>();
    boolean truncated = false;
    for (ScreenActivityEvent event : events) {
      if (event.sequence() <= since) continue;
      if (limit <= 0 || page.size() >= limit) {
        truncated = true;
        break;
      }
      page.add(event);
    }
    long nextSince = page.isEmpty() ? since : page.get(page.size() - 1).sequence();
    return new History(recording, List.copyOf(page), nextSince, truncated);
  }

  /** 当前保留的事件条数（测试与诊断用，不返回内容）。 */
  public synchronized int size() {
    prune(clock.getAsLong());
    return events.size();
  }

  private boolean matches(String identity) {
    return recording && identity != null && identity.equals(taskIdentity);
  }

  private void prune(long now) {
    while (!events.isEmpty() && now - events.peekFirst().atMillis() > windowMillis) {
      events.removeFirst();
    }
  }

  /** 一次增量查询的结果；{@code nextSince} 可直接作为下一次查询的 since。 */
  public record History(
      boolean recording, List<ScreenActivityEvent> events, long nextSince, boolean truncated) {}
}
