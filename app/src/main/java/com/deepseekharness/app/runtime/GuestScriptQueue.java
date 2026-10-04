package com.deepseekharness.app.runtime;

import java.io.IOException;
import java.util.ArrayDeque;

/** 只在登记与交接时持有 monitor；脚本在原调用线程执行并保留其维护所有权。 */
final class GuestScriptQueue {
  private final Object lock = new Object();
  private final ArrayDeque<Ticket> waiting = new ArrayDeque<>();
  private final int limit;
  private Ticket active;
  private int depth;

  private record Ticket(Thread owner) {}

  GuestScriptQueue(int limit) {
    if (limit < 1) throw new IllegalArgumentException("SCRIPT_QUEUE_LIMIT");
    this.limit = limit;
  }

  Lease enter() throws IOException, InterruptedException {
    if (Thread.interrupted()) throw new InterruptedException("SCRIPT_QUEUE_CANCELLED");
    synchronized (lock) {
      if (active != null && active.owner == Thread.currentThread()) {
        depth++;
        return new Lease(active);
      }
      if (waiting.size() >= limit) throw new IOException("SCRIPT_QUEUE_LIMIT");
      Ticket ticket = new Ticket(Thread.currentThread());
      waiting.addLast(ticket);
      try {
        while (active != null || waiting.peekFirst() != ticket) lock.wait();
      } catch (InterruptedException cancelled) {
        waiting.remove(ticket);
        lock.notifyAll();
        throw cancelled;
      }
      waiting.removeFirst();
      active = ticket;
      depth = 1;
      return new Lease(ticket);
    }
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
