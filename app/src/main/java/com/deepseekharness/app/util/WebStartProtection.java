package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Application-session startup protection; never persists or replays a launch request. */
public final class WebStartProtection {
  public static final long PROMOTION_TIMEOUT_MS = 8_000;
  public static final long PREPARATION_TIMEOUT_MS = 120_000;
  private final WebLifecycle lifecycle;
  private final LongSupplier clock;
  private Ticket pending;
  private Ticket queued;

  public WebStartProtection(WebLifecycle lifecycle, LongSupplier clock) {
    this.lifecycle = lifecycle;
    this.clock = clock;
  }

  public final class Ticket implements AutoCloseable {
    private final String id;
    private final long created, deadline;
    private final CountDownLatch promotion = new CountDownLatch(1);
    private long generation;
    private Object service;
    private BooleanSupplier serviceCurrent;
    private boolean waitingStop, admitted, claimed, closed;
    private String failure = "";

    private Ticket(long generation) {
      id = java.util.UUID.randomUUID().toString();
      this.generation = generation;
      created = clock.getAsLong();
      deadline = created + PREPARATION_TIMEOUT_MS;
    }

    public String id() {
      return id;
    }

    public long generation() {
      synchronized (lifecycle) {
        return generation;
      }
    }

    public void awaitPromotion() throws IOException {
      try {
        if (!promotion.await(PROMOTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
          reject(this, "WEB_FOREGROUND_PROMOTION_TIMEOUT");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        reject(this, "WEB_FOREGROUND_PROMOTION_INTERRUPTED");
        throw new IOException("WEB_FOREGROUND_PROMOTION_INTERRUPTED", interrupted);
      }
      synchronized (lifecycle) {
        if (!ready(this))
          throw new IOException(failure.isEmpty() ? "WEB_START_PROTECTION_UNAVAILABLE" : failure);
      }
    }

    /** Cancels only preparation; an admitted runtime now belongs to its normal generation. */
    @Override
    public void close() {
      reject(this, "WEB_START_PREPARATION_CLOSED");
    }
  }

  public Ticket begin() {
    synchronized (lifecycle) {
      if (active() != null || lifecycle.isStopping()) return null;
      return pending = new Ticket(lifecycle.generation());
    }
  }

  private Ticket active() {
    Ticket ticket = pending;
    if (ticket == null) return null;
    long now = clock.getAsLong();
    if (ticket.closed
        || ticket.admitted
        || now < ticket.created
        || now >= ticket.deadline
        || lifecycle.generation() != ticket.generation) {
      reject(ticket, "WEB_START_PREPARATION_EXPIRED_OR_SUPERSEDED");
      return null;
    }
    if (ticket.serviceCurrent != null) {
      boolean current;
      try {
        current = ticket.serviceCurrent.getAsBoolean();
      } catch (RuntimeException unavailable) {
        current = false;
      }
      if (!current) {
        reject(ticket, "WEB_FOREGROUND_SERVICE_LOST");
        return null;
      }
    }
    return ticket;
  }

  public boolean hasPreparing() {
    synchronized (lifecycle) {
      return active() != null;
    }
  }

  public boolean matches(String id) {
    synchronized (lifecycle) {
      Ticket ticket = active();
      return ticket != null && ticket.id.equals(id);
    }
  }

  public boolean promote(String id, Object service, BooleanSupplier serviceCurrent) {
    synchronized (lifecycle) {
      Ticket ticket = active();
      if (ticket == null || !ticket.id.equals(id) || service == null || serviceCurrent == null)
        return false;
      if (ticket.service != null && ticket.service != service) return false;
      if (!serviceCurrent.getAsBoolean()) return false;
      ticket.service = service;
      ticket.serviceCurrent = serviceCurrent;
      ticket.promotion.countDown();
      return true;
    }
  }

  private boolean ready(Ticket ticket) {
    return ticket != null && active() == ticket && ticket.service != null;
  }

  public boolean canAdmit(Ticket ticket) {
    synchronized (lifecycle) {
      return ready(ticket) && !ticket.waitingStop;
    }
  }

  /** Validate first; no fallible read follows the accepted lifecycle transition. */
  public long beginStart(Ticket ticket, boolean stopSentinelPresent) {
    synchronized (lifecycle) {
      if (!canAdmit(ticket)) return -1;
      long generation = lifecycle.beginStart(false, stopSentinelPresent);
      if (generation < 0) return -1;
      ticket.generation = generation;
      ticket.admitted = true;
      pending = null;
      if (queued != null) queued.closed = true;
      queued = ticket;
      return generation;
    }
  }

  /** Only the precise pending capability may cross its own recovery Stop. */
  public long beginInternalStop(Ticket ticket) {
    synchronized (lifecycle) {
      if (!canAdmit(ticket) || lifecycle.isStopping()) return -1;
      long stopped = lifecycle.beginStop();
      ticket.generation = stopped;
      ticket.waitingStop = true;
      return stopped;
    }
  }

  public boolean resumeAfterStop(Ticket ticket) {
    synchronized (lifecycle) {
      if (!ready(ticket) || !ticket.waitingStop || lifecycle.isStopping()) return false;
      ticket.waitingStop = false;
      return true;
    }
  }

  public void cancelAll() {
    synchronized (lifecycle) {
      if (pending != null) reject(pending, "WEB_START_CANCELLED_BY_NEW_INTENT");
      if (queued != null) {
        queued.closed = true;
        queued = null;
      }
    }
  }

  /** The admitted worker must take ownership before doing any runtime work. */
  public boolean claimQueuedStart(Ticket ticket, long generation) {
    synchronized (lifecycle) {
      if (ticket == null
          || queued != ticket
          || ticket.closed
          || ticket.claimed
          || ticket.generation != generation
          || !lifecycle.isCurrent(generation)) return false;
      boolean current;
      try {
        current = ticket.serviceCurrent != null && ticket.serviceCurrent.getAsBoolean();
      } catch (RuntimeException unavailable) {
        current = false;
      }
      if (!current) {
        ticket.closed = true;
        queued = null;
      } else ticket.claimed = true;
      return current;
    }
  }

  /** Returns only this service's still-current admitted runtime for a fenced safe Stop. */
  public long serviceDestroyed(Object service) {
    synchronized (lifecycle) {
      if (pending != null && pending.service == service)
        reject(pending, "WEB_FOREGROUND_SERVICE_DESTROYED");
      if (queued != null && queued.service == service) {
        long generation = queued.generation;
        queued.closed = true;
        queued = null;
        return lifecycle.isCurrent(generation) ? generation : -1;
      }
      return -1;
    }
  }

  public void reject(String id, String reason) {
    synchronized (lifecycle) {
      if (pending != null && pending.id.equals(id)) reject(pending, reason);
    }
  }

  private void reject(Ticket ticket, String reason) {
    synchronized (lifecycle) {
      if (ticket == null || ticket.admitted || ticket.closed) return;
      ticket.closed = true;
      ticket.failure = reason;
      if (pending == ticket) pending = null;
      ticket.promotion.countDown();
    }
  }
}
