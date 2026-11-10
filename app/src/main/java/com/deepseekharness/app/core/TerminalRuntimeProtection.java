package com.deepseekharness.app.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 终端专属前台准入；逻辑租约不是进程退出证明，也不承担 RuntimeTasks 的维护屏障。 */
public final class TerminalRuntimeProtection {
  public static final long PROMOTION_TIMEOUT_MS = 8_000;
  public static final long PREPARATION_TIMEOUT_MS = 120_000;

  public enum Kind {
    PTY,
    SIMPLE
  }

  public enum State {
    PREPARING,
    ACTIVE,
    LOST,
    RELEASED
  }

  public interface Admission {
    void onReady(Ticket ticket);

    void onRejected(Ticket ticket, String reason);
  }

  public static final class Snapshot {
    public final State state;
    public final int preparing, creating, held;
    public final String error;

    Snapshot(State state, int preparing, int creating, int held, String error) {
      this.state = state;
      this.preparing = preparing;
      this.creating = creating;
      this.held = held;
      this.error = error;
    }
  }

  private static final TerminalRuntimeProtection SHARED =
      new TerminalRuntimeProtection(() -> System.nanoTime() / 1_000_000);
  private final LongSupplier clock;
  private final Map<String, Ticket> tickets = new LinkedHashMap<>();
  private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
  private long generation;

  public TerminalRuntimeProtection(LongSupplier clock) {
    this.clock = clock;
  }

  public static TerminalRuntimeProtection shared() {
    return SHARED;
  }

  public final class Ticket implements AutoCloseable {
    private final TerminalRuntimeProtection owner = TerminalRuntimeProtection.this;
    private final String id = UUID.randomUUID().toString();
    private final String ownerEpoch;
    private final Kind kind;
    private final long generation, created;
    private Admission admission;
    private Object service;
    private BooleanSupplier current;
    private boolean promoted, started, finished, hold, released, lost;
    private String failure = "";

    private Ticket(String ownerEpoch, Kind kind, Admission admission) {
      this.ownerEpoch = ownerEpoch;
      this.kind = kind;
      this.admission = admission;
      this.generation = ++TerminalRuntimeProtection.this.generation;
      this.created = clock.getAsLong();
    }

    public String id() {
      return id;
    }

    public String ownerEpoch() {
      return ownerEpoch;
    }

    public Kind kind() {
      return kind;
    }

    public long generation() {
      return generation;
    }

    public boolean hasStarted() {
      synchronized (owner) {
        return started;
      }
    }

    public State state() {
      synchronized (owner) {
        if (released) return State.RELEASED;
        if (lost) return State.LOST;
        return promoted ? State.ACTIVE : State.PREPARING;
      }
    }

    public String error() {
      synchronized (owner) {
        return failure;
      }
    }

    /** 页面离开只可撤销尚未被工作线程接管的票；绝不取消已 fork 的寿命。 */
    public boolean cancel() {
      return reject(this, "TERMINAL_CREATION_CANCELLED");
    }

    @Override
    public void close() {
      cancel();
    }
  }

  /** 仅由已经核对可见宿主的 Android 适配层调用；本类不持有 Activity。 */
  public Ticket prepare(String ownerEpoch, Kind kind, Admission admission) {
    if (ownerEpoch == null || ownerEpoch.isEmpty() || kind == null || admission == null)
      throw new IllegalArgumentException("TERMINAL_INVALID_PREPARATION");
    Ticket ticket;
    synchronized (this) {
      ticket = new Ticket(ownerEpoch, kind, admission);
      tickets.put(ticket.id, ticket);
    }
    changed();
    return ticket;
  }

  private boolean exact(Ticket ticket) {
    return ticket != null && ticket.owner == this && tickets.get(ticket.id) == ticket;
  }

  public synchronized boolean matches(String id) {
    Ticket ticket = tickets.get(id);
    return ticket != null && !ticket.released && !ticket.started && !expired(ticket);
  }

  private boolean expired(Ticket ticket) {
    long now = clock.getAsLong();
    long limit = ticket.promoted ? PREPARATION_TIMEOUT_MS : PROMOTION_TIMEOUT_MS;
    return now < ticket.created || now - ticket.created >= limit;
  }

  private static boolean current(Ticket ticket) {
    try {
      return ticket.current != null && ticket.current.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /** 必须在真实 startForeground 成功后，以同一服务实例回调。 */
  public boolean promoted(String id, Object service, BooleanSupplier serviceCurrent) {
    Ticket ticket;
    Admission callback;
    synchronized (this) {
      ticket = tickets.get(id);
      if (ticket == null
          || ticket.released
          || ticket.started
          || ticket.promoted
          || expired(ticket)
          || service == null
          || serviceCurrent == null) return false;
      ticket.service = service;
      ticket.current = serviceCurrent;
      if (!current(ticket)) return false;
      ticket.promoted = true;
      callback = ticket.admission;
      ticket.admission = null;
    }
    changed();
    if (callback != null) {
      try {
        callback.onReady(ticket);
      } catch (RuntimeException failure) {
        ticket.cancel();
      }
    }
    return true;
  }

  /** 精确对象、ownerEpoch、类型及服务身份同锁消费一次；不是“存在任意票”守卫。 */
  public void beginCreate(Ticket ticket, String ownerEpoch, Kind kind) throws IOException {
    String failure = "";
    synchronized (this) {
      if (!exact(ticket)
          || ticket.released
          || ticket.started
          || ticket.kind != kind
          || !ticket.ownerEpoch.equals(ownerEpoch))
        throw new IOException("TERMINAL_CREATE_TICKET_MISMATCH");
      if (!ticket.promoted || expired(ticket)) failure = "TERMINAL_CREATE_TICKET_EXPIRED";
      else if (ticket.lost || !current(ticket)) failure = "TERMINAL_FOREGROUND_SERVICE_LOST";
      if (failure.isEmpty()) ticket.started = true;
    }
    if (!failure.isEmpty()) {
      reject(ticket, failure);
      throw new IOException(failure);
    }
    changed();
  }

  /** 在任何可能 fork 的工作之前登记；创建 finally 结束前即使释放也仍有准备保护。 */
  void retain(Ticket ticket) {
    synchronized (this) {
      if (!exact(ticket) || !ticket.started || ticket.finished || ticket.released)
        throw new IllegalStateException("TERMINAL_HOLD_TICKET_MISMATCH");
      ticket.hold = true;
    }
    changed();
  }

  /** 底层成功核验退出，或明确未创建；不接受 isRunning/FAILED 代替这条证据。 */
  public void releaseVerified(Ticket ticket) {
    synchronized (this) {
      if (!exact(ticket) || ticket.released) return;
      ticket.hold = false;
      if (ticket.finished) remove(ticket);
    }
    changed();
  }

  /** 创建调用必须 finally 收束；已登记但未核验的 hold 继续留存，包括未发布 PTY。 */
  void finishCreate(Ticket ticket) {
    synchronized (this) {
      if (!exact(ticket) || !ticket.started || ticket.finished) return;
      ticket.finished = true;
      if (!ticket.hold) remove(ticket);
    }
    changed();
  }

  /** 简易后端重启仍需原服务存活；失去保护不暗中重新提升/重放 shell。 */
  public synchronized void requireHeld(Ticket ticket) throws IOException {
    if (!exact(ticket)
        || ticket.released
        || !ticket.started
        || !ticket.hold
        || ticket.lost
        || !current(ticket)) throw new IOException("TERMINAL_FOREGROUND_SERVICE_LOST");
  }

  public synchronized boolean isProtected(Ticket ticket) {
    return exact(ticket) && !ticket.released && !ticket.lost && ticket.promoted && current(ticket);
  }

  public void expire(Ticket ticket) {
    synchronized (this) {
      if (!exact(ticket) || ticket.started || !expired(ticket)) return;
    }
    reject(ticket, "TERMINAL_FOREGROUND_PROMOTION_TIMEOUT");
  }

  public void reject(String id, String reason) {
    Ticket ticket;
    synchronized (this) {
      ticket = tickets.get(id);
    }
    reject(ticket, reason);
  }

  private boolean reject(Ticket ticket, String reason) {
    Admission callback;
    synchronized (this) {
      if (!exact(ticket) || ticket.released || ticket.started) return false;
      ticket.failure = reason == null ? "TERMINAL_FOREGROUND_UNAVAILABLE" : reason;
      callback = ticket.admission;
      ticket.admission = null;
      remove(ticket);
    }
    changed();
    if (callback != null) {
      try {
        callback.onRejected(ticket, ticket.failure);
      } catch (RuntimeException ignored) {
      }
    }
    return true;
  }

  private void remove(Ticket ticket) {
    ticket.released = true;
    ticket.admission = null;
    ticket.current = null;
    ticket.service = null;
    tickets.remove(ticket.id);
  }

  /** 服务丢失不代表任何进程退出；运行中与创建中的逻辑票保留为 LOST。 */
  public void serviceDestroyed(Object service) {
    List<Ticket> rejected = new ArrayList<>();
    synchronized (this) {
      for (Ticket ticket : tickets.values()) {
        if (ticket.service != service) continue;
        ticket.lost = true;
        ticket.failure = "TERMINAL_FOREGROUND_SERVICE_LOST";
        ticket.current = null;
        ticket.service = null;
        if (!ticket.started) rejected.add(ticket);
      }
    }
    for (Ticket ticket : rejected) reject(ticket, "TERMINAL_FOREGROUND_SERVICE_LOST");
    changed();
  }

  public synchronized boolean hasDemand() {
    return !tickets.isEmpty();
  }

  /** 服务只能为属于本实例的票保活；已 LOST 的旧票不能借新会话变成 ACTIVE。 */
  public synchronized boolean needsService(Object service) {
    for (Ticket ticket : tickets.values())
      if (!ticket.released && !ticket.lost && (ticket.service == service || !ticket.promoted))
        return true;
    return false;
  }

  public synchronized Snapshot snapshot() {
    int preparing = 0, creating = 0, held = 0;
    boolean lost = false, active = false;
    for (Ticket ticket : tickets.values()) {
      if (!ticket.started) preparing++;
      else if (!ticket.finished) creating++;
      if (ticket.hold) held++;
      lost |= ticket.lost || (ticket.promoted && !current(ticket));
      active |= ticket.promoted;
    }
    State state =
        tickets.isEmpty()
            ? State.RELEASED
            : lost ? State.LOST : active ? State.ACTIVE : State.PREPARING;
    return new Snapshot(
        state, preparing, creating, held, lost ? "TERMINAL_FOREGROUND_SERVICE_LOST" : "");
  }

  public void addObserver(Runnable observer) {
    observers.addIfAbsent(observer);
  }

  public void removeObserver(Runnable observer) {
    observers.remove(observer);
  }

  private void changed() {
    for (Runnable observer : observers) {
      try {
        observer.run();
      } catch (RuntimeException ignored) {
      }
    }
  }
}
