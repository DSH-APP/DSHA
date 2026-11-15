package com.deepseekharness.app.control;

import java.util.Objects;
import java.util.HashMap;
import java.util.Map;

/** One physical screen owner. Every transition fences queued work before cancellation callbacks. */
public final class ControlTaskAuthority {
  public enum Owner {
    LOCAL_DSH
  }

  public record Binding(Owner owner, String connection, String taskSessionId, String turnId) {
    public Binding {
      Objects.requireNonNull(owner);
      check(connection);
      check(taskSessionId);
      check(turnId);
    }

    private static void check(String value) {
      if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0)
        throw new IllegalArgumentException("CONTROL_ID_INVALID");
    }

    public String identity() {
      return owner + "\0" + connection + "\0" + taskSessionId + "\0" + turnId;
    }
  }

  public record Lease(Binding binding, long revision) {}

  public record Snapshot(
      Binding binding, long revision, boolean paused, boolean needsObservation, String reason) {}

  private Binding binding;
  private long revision;
  private boolean paused, needsObservation = true;
  private com.deepseekharness.app.util.ScreenTarget observedTarget;
  private long observationSequence;
  private String reason = "IDLE";
  // Local Agent turn numbers are monotonic within a full conversation. Keep
  // completion watermarks so a delayed old request cannot reacquire the screen.
  private final Map<String, Long> completedLocalTurns = new HashMap<>();

  public synchronized void checkBegin(Binding requested) {
    long turn = localTurn(requested.turnId());
    String key = localKey(requested);
    if (turn <= completedLocalTurns.getOrDefault(key, 0L))
      throw new IllegalStateException("SCREEN_TURN_ENDED");
    if (!completedLocalTurns.containsKey(key) && completedLocalTurns.size() >= 4096)
      throw new IllegalStateException("CONTROL_SESSION_LIMIT");
    if (paused) throw new IllegalStateException("SCREEN_CONTROL_PAUSED");
    if (binding != null && !binding.equals(requested))
      throw new IllegalStateException("PHYSICAL_SCREEN_BUSY");
  }

  public synchronized Lease begin(Binding requested) {
    checkBegin(requested);
    // Reserve before accepting work, so unrelated ended conversations cannot
    // fill the bound and prevent this turn's retirement.
    completedLocalTurns.putIfAbsent(localKey(requested), 0L);
    if (binding == null) {
      binding = requested;
      revision++;
      needsObservation = true;
      reason = "OBSERVE_REQUIRED";
    }
    return new Lease(binding, revision);
  }

  private static String localKey(Binding requested) {
    return requested.connection() + "\0" + requested.taskSessionId();
  }

  /** The pinned local Agent uses positive safe integer turn numbers. */
  public static long localTurn(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,15}"))
      throw new IllegalArgumentException("CONTROL_TURN_INVALID");
    long turn = Long.parseLong(value);
    if (turn > 9007199254740991L) throw new IllegalArgumentException("CONTROL_TURN_INVALID");
    return turn;
  }

  /** An exact actual turn/end; never cancels another conversation or a newer turn. */
  public synchronized boolean completeLocal(Binding ended) {
    long turn = localTurn(ended.turnId());
    String key = localKey(ended);
    // Bound hostile/invalid traffic without evicting a live completion fence.
    if (!completedLocalTurns.containsKey(key) && completedLocalTurns.size() >= 4096)
      throw new IllegalStateException("CONTROL_SESSION_LIMIT");
    completedLocalTurns.merge(key, turn, Math::max);
    if (binding == null
        || !binding.connection().equals(ended.connection())
        || !binding.taskSessionId().equals(ended.taskSessionId())
        || localTurn(binding.turnId()) > turn) return false;
    binding = null;
    needsObservation = true;
    revision++;
    if (!paused) reason = "LOCAL_TURN_ENDED";
    return true;
  }

  public synchronized Lease lease(Binding requested) {
    if (binding == null || !binding.equals(requested) || paused)
      throw new IllegalStateException("SCREEN_TASK_CHANGED");
    return new Lease(binding, revision);
  }

  public synchronized boolean current(Lease lease) {
    return lease != null
        && !paused
        && binding != null
        && binding.equals(lease.binding)
        && revision == lease.revision;
  }

  public synchronized boolean actionAllowed(Lease lease) {
    return current(lease) && !needsObservation;
  }

  public synchronized boolean observed(Lease lease) {
    if (!current(lease)) return false;
    observedTarget = null;
    needsObservation = false;
    reason = "ACTIVE";
    return true;
  }

  /** Invalidate prior evidence before a new observation starts, including on failure. */
  public synchronized long beginObservation(Lease lease) {
    if (!current(lease)) return -1;
    needsObservation = true;
    observedTarget = null;
    reason = "OBSERVE_REQUIRED";
    return ++observationSequence;
  }

  /** Only the most recently requested observation can establish an action target. */
  public synchronized boolean observed(
      Lease lease, long sequence, com.deepseekharness.app.util.ScreenTarget target) {
    if (!current(lease)
        || sequence != observationSequence
        || target == null
        || target.displayId != 0
        || target.windowId < 0
        || target.packageName.isEmpty()) return false;
    observedTarget = target;
    needsObservation = false;
    reason = "ACTIVE";
    return true;
  }

  public synchronized boolean actionAllowed(
      Lease lease, com.deepseekharness.app.util.ScreenTarget target) {
    return actionAllowed(lease) && observedTarget != null && observedTarget.matches(target);
  }

  /** A changed target consumes prior evidence; returning to it still requires a fresh read. */
  public synchronized boolean checkActionTarget(
      Lease lease, com.deepseekharness.app.util.ScreenTarget target) {
    if (actionAllowed(lease, target)) return true;
    if (current(lease)) {
      needsObservation = true;
      observedTarget = null;
      reason = "OBSERVE_REQUIRED";
    }
    return false;
  }

  /** Changing an owned window invalidates pixels/nodes without cancelling or resuming the task. */
  public synchronized void requireObservation(Binding requested) {
    if (paused) throw new IllegalStateException("SCREEN_CONTROL_PAUSED");
    if (binding != null && !binding.equals(requested))
      throw new IllegalStateException("SCREEN_TASK_CHANGED");
    needsObservation = true;
    revision++;
    reason = "OBSERVE_REQUIRED";
  }

  public synchronized Binding pause(String why) {
    Binding previous = binding;
    paused = true;
    needsObservation = true;
    revision++;
    reason = why;
    return previous;
  }

  /** An asynchronous observer can pause only the exact lease it actually checked. */
  public synchronized Binding pauseCurrent(Lease lease, String why) {
    if (!current(lease)) return null;
    return pause(why);
  }

  public synchronized void resume() {
    paused = false;
    needsObservation = true;
    revision++;
    reason = "OBSERVE_REQUIRED";
  }

  public synchronized Binding stop(String why) {
    Binding previous = binding;
    binding = null;
    needsObservation = true;
    revision++;
    if (!paused) reason = why;
    return previous;
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(binding, revision, paused, needsObservation, reason);
  }
}
