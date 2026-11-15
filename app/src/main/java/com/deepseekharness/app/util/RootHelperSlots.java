package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * root helper（su → app_process RootShellMain）的并发槽位。
 *
 * <p>T38 真机证据：App 的 uid 对 su 提权后的 uid 0 子进程发信号会得到 EPERM，超时后无法结束它，
 * 只能等 helper 自己退出。因此槽位按「进程真正退出」释放：超时未退出的 helper 继续占用槽位，
 * 新请求在发送前就被拒绝（命令未发送，可安全重试），而不是一直叠加新的 root 进程。
 *
 * <p>纯逻辑；进程是否存活由调用方提供的判定决定，不依赖 Android。
 */
public final class RootHelperSlots<P> {
  /** 一个已占用的槽位；release 后如进程仍存活，则转为滞留并继续占用。 */
  public static final class Ticket<P> {
    final long acquiredAt;
    final String label;
    P process;
    long lingeringSince = -1;

    Ticket(long acquiredAt, String label) {
      this.acquiredAt = acquiredAt;
      this.label = label;
    }
  }

  /** 只读快照，用于诊断记录与报错文案。 */
  public static final class Snapshot {
    public final int capacity, active, lingering;
    public final long oldestLingeringMs;
    public final List<String> labels;

    Snapshot(int capacity, int active, int lingering, long oldestLingeringMs, List<String> labels) {
      this.capacity = capacity;
      this.active = active;
      this.lingering = lingering;
      this.oldestLingeringMs = oldestLingeringMs;
      this.labels = labels;
    }

    public boolean full() {
      return active + lingering >= capacity;
    }

    public String render() {
      return "capacity="
          + capacity
          + " active="
          + active
          + " lingering="
          + lingering
          + (lingering > 0 ? " oldestLingeringMs=" + oldestLingeringMs : "")
          + (labels.isEmpty() ? "" : " labels=" + String.join(",", labels));
    }
  }

  private final int capacity;
  private final Predicate<P> alive;
  private final List<Ticket<P>> tickets = new ArrayList<>();

  public RootHelperSlots(int capacity, Predicate<P> alive) {
    if (capacity < 1 || capacity > 16) throw new IllegalArgumentException("ROOT_SLOT_CAPACITY");
    if (alive == null) throw new IllegalArgumentException("ROOT_SLOT_ALIVE");
    this.capacity = capacity;
    this.alive = alive;
  }

  /** 先回收已退出的滞留 helper；仍满时返回 null，调用方必须在启动 su 之前拒绝。 */
  public synchronized Ticket<P> tryAcquire(String label, long now) {
    prune();
    if (tickets.size() >= capacity) return null;
    Ticket<P> ticket = new Ticket<>(now, label == null || label.isEmpty() ? "exec" : label);
    tickets.add(ticket);
    return ticket;
  }

  /** 进程启动后登记句柄；滞留判定只看这一个本次进程。 */
  public synchronized void attach(Ticket<P> ticket, P process) {
    if (ticket != null && tickets.contains(ticket)) ticket.process = process;
  }

  /**
   * 请求结束时调用。返回 true 表示 helper 仍未退出、槽位继续被占用（滞留）。
   *
   * <p>未启动进程或已确认退出的槽位立即释放；重复调用无副作用。
   */
  public synchronized boolean release(Ticket<P> ticket, long now) {
    if (ticket == null || !tickets.contains(ticket)) return false;
    if (ticket.process != null && alive.test(ticket.process)) {
      if (ticket.lingeringSince < 0) ticket.lingeringSince = now;
      return true;
    }
    tickets.remove(ticket);
    return false;
  }

  public synchronized Snapshot snapshot(long now) {
    prune();
    int lingering = 0;
    long oldest = 0;
    List<String> labels = new ArrayList<>();
    for (Ticket<P> ticket : tickets) {
      if (ticket.lingeringSince >= 0) {
        lingering++;
        oldest = Math.max(oldest, now - ticket.lingeringSince);
        labels.add(ticket.label + "(lingering)");
      } else labels.add(ticket.label);
    }
    return new Snapshot(capacity, tickets.size() - lingering, lingering, oldest, labels);
  }

  private void prune() {
    for (Iterator<Ticket<P>> it = tickets.iterator(); it.hasNext(); ) {
      Ticket<P> ticket = it.next();
      if (ticket.lingeringSince >= 0 && (ticket.process == null || !alive.test(ticket.process)))
        it.remove();
    }
  }
}
