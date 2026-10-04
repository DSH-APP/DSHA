package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One delayed producer shared by a bounded set of owners; no per-frame task or thread growth. */
public final class CoalescingPoller<T> {
  private final ScheduledExecutorService worker;
  private final Callable<T> producer;
  private final int maximumOwners;
  private final long delayMillis;
  private final Map<Object, Subscription> owners = new LinkedHashMap<>();
  private ScheduledFuture<?> scheduled;
  private long scheduleRevision;

  public CoalescingPoller(
      ScheduledExecutorService worker, Callable<T> producer, int maximumOwners, long delayMillis) {
    if (maximumOwners < 1 || delayMillis < 1) throw new IllegalArgumentException("POLL_LIMIT");
    this.worker = worker;
    this.producer = producer;
    this.maximumOwners = maximumOwners;
    this.delayMillis = delayMillis;
  }

  public final class Subscription implements AutoCloseable {
    private final Object owner;
    private final Consumer<T> listener;
    private volatile boolean closed;

    private Subscription(Object owner, Consumer<T> listener) {
      this.owner = owner;
      this.listener = listener;
    }

    public boolean closed() {
      return closed;
    }

    @Override
    public void close() {
      synchronized (CoalescingPoller.this) {
        if (closed) return;
        closed = true;
        if (owners.get(owner) == this) owners.remove(owner);
        if (owners.isEmpty() && scheduled != null) {
          scheduled.cancel(false);
          scheduled = null;
          scheduleRevision++;
        }
      }
    }
  }

  public synchronized Subscription subscribe(Object owner, Consumer<T> listener) {
    Subscription old = owners.get(owner);
    if (old != null) old.close();
    if (owners.size() >= maximumOwners) throw new IllegalStateException("POLL_OWNER_LIMIT");
    Subscription next = new Subscription(owner, listener);
    owners.put(owner, next);
    if (scheduled == null) {
      long revision = ++scheduleRevision;
      scheduled =
          worker.scheduleWithFixedDelay(
              () -> poll(revision), 0, delayMillis, TimeUnit.MILLISECONDS);
    }
    return next;
  }

  private void poll(long revision) {
    ArrayList<Subscription> recipients;
    synchronized (this) {
      if (revision != scheduleRevision || owners.isEmpty()) return;
      recipients = new ArrayList<>(owners.values());
    }
    T value;
    try {
      value = producer.call();
    } catch (Exception unavailable) {
      return;
    }
    for (Subscription recipient : recipients) {
      if (!recipient.closed) {
        try {
          recipient.listener.accept(value);
        } catch (RuntimeException isolatedConsumer) {
          /* Other live owners still receive this frame. */
        }
      }
    }
  }
}
