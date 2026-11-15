package com.deepseekharness.app.runtime;

import java.io.IOException;
import java.util.ArrayDeque;

/** 只在登记与交接时持有 monitor；脚本在原调用线程执行并保留其维护所有权。 */
final class GuestScriptQueue {
  private final Object lock = new Object();
  private final ArrayDeque<Ticket> high = new ArrayDeque<>();
  private final ArrayDeque<Ticket> normal = new ArrayDeque<>();
  private final int limit;
  private Ticket active;
  private int depth;

  private record Ticket(Thread owner) {}

  GuestScriptQueue(int limit) {
    if (limit < 1) throw new IllegalArgumentException("SCRIPT_QUEUE_LIMIT");
    this.limit = limit;
  }

  Lease enter() throws IOException, InterruptedException {
    return enter(null, false);
  }

  Lease enter(GuestScriptBudget budget) throws IOException, InterruptedException {
    return enter(budget, false);
  }

  /**
   * 启动/用户修改优先：不能抢占已经运行的脚本，但可以跳过等待中的普通查询；
   * 有 budget 时等待有界，超时返回明确错误而不是无限排队。
   */
  Lease enter(GuestScriptBudget budget, boolean highPriority)
      throws IOException, InterruptedException {
    if (Thread.interrupted()) throw new InterruptedException("SCRIPT_QUEUE_CANCELLED");
    if (budget != null) budget.remainingMillis();
    synchronized (lock) {
      if (active != null && active.owner == Thread.currentThread()) {
        depth++;
        return new Lease(active);
      }
      if (high.size() + normal.size() >= limit) throw new IOException("SCRIPT_QUEUE_LIMIT");
      Ticket ticket = new Ticket(Thread.currentThread());
      ArrayDeque<Ticket> lane = highPriority ? high : normal;
      lane.addLast(ticket);
      try {
        while (active != null || nextTicket() != ticket) {
          if (budget == null) lock.wait();
          else lock.wait(Math.min(100, budget.remainingMillis()));
        }
        if (budget != null) budget.remainingMillis();
      } catch (IOException | InterruptedException | RuntimeException | Error cancelled) {
        lane.remove(ticket);
        lock.notifyAll();
        throw cancelled;
      }
      lane.removeFirst();
      active = ticket;
      depth = 1;
      return new Lease(ticket);
    }
  }

  private Ticket nextTicket() {
    return high.isEmpty() ? normal.peekFirst() : high.peekFirst();
  }

  final class Lease implements AutoCloseable {
    private final Ticket ticket;
    private boolean closed;

    private Lease(Ticket ticket) {
      this.ticket = ticket;
    }

    @Override
    public void close() {
      synchronized (lock) {
        if (closed) return;
        if (active != ticket || ticket.owner != Thread.currentThread())
          throw new IllegalStateException("SCRIPT_QUEUE_OWNER_CHANGED");
        closed = true;
        if (--depth == 0) {
          active = null;
          lock.notifyAll();
        }
      }
    }
  }
}
