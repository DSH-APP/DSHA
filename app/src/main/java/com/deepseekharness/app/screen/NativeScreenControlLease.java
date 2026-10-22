package com.deepseekharness.app.screen;

import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.util.ScreenConnectionEpoch;
import com.deepseekharness.app.util.ScreenTarget;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Explicit native user controls; never creates or borrows an assistant capability. */
public final class NativeScreenControlLease implements AutoCloseable {
  private final ScreenConnectionEpoch<DshaAccessibilityService> connection;
  private final ScreenConnectionEpoch.Lease<DshaAccessibilityService> service;
  private final BooleanSupplier authority;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicInteger display = new AtomicInteger(-1);
  private final AtomicReference<Action> active = new AtomicReference<>();

  NativeScreenControlLease(
      ScreenConnectionEpoch<DshaAccessibilityService> connection,
      com.deepseekharness.app.vscreen.VirtualScreenNativeControls subject) {
    this.connection = connection;
    this.authority = java.util.Objects.requireNonNull(subject)::nativeAuthorityCurrent;
    service = connection.snapshot();
  }

  public DshaAccessibilityService service() {
    return service == null ? null : service.service;
  }

  public boolean current() {
    try {
      return !closed.get()
          && connection.current(service)
          && authority != null
          && authority.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  public Action begin(int displayId, ScreenTarget target, boolean readingTree) {
    if (displayId <= 0 || !current()) return null;
    display.compareAndSet(-1, displayId);
    if (display.get() != displayId || !readingTree && target == null) return null;
    Action action = new Action(target, readingTree);
    if (!active.compareAndSet(null, action)) return null;
    if (!current()) {
      action.close();
      return null;
    }
    return action;
  }

  @Override
  public void close() {
    closed.set(true);
    Action action = active.get();
    if (action != null) action.cancel();
  }

  public final class Action implements AutoCloseable {
    private enum Phase {
      READY,
      CLAIMING,
      ADMITTED,
      FAILED,
      CANCELLED
    }

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.READY);
    private final AtomicBoolean mayHaveSubmitted = new AtomicBoolean();
    private final boolean readingTree;
    private ScreenTarget target;

    private Action(ScreenTarget target, boolean readingTree) {
      this.target = target;
      this.readingTree = readingTree;
    }

    public boolean current() {
      return active.get() == this
          && phase.get() != Phase.CANCELLED
          && NativeScreenControlLease.this.current();
    }

    public void validateRoot(int displayId, AccessibilityNodeInfo root) {
      if (!current() || display.get() != displayId)
        throw new DshaAccessibilityService.TargetChangedException();
      ScreenTarget actual = ScreenTargetResolver.identity(displayId, root);
      if (actual == null || actual.windowId < 0 || actual.packageName.isEmpty())
        throw new DshaAccessibilityService.TargetChangedException();
      if (target == null && readingTree) target = actual;
      if (target == null || !target.matches(actual))
        throw new DshaAccessibilityService.TargetChangedException();
    }

    public void validateCurrentTarget() {
      if (!current() || target == null) throw new DshaAccessibilityService.TargetChangedException();
      try (var latest = ScreenTargetResolver.resolve(service(), display.get())) {
        validateRoot(display.get(), latest.root);
      }
    }

    public boolean claimDispatch() {
      if (!current()) return false;
      if (!phase.compareAndSet(Phase.READY, Phase.CLAIMING)) return false;
      mayHaveSubmitted.set(true);
      try {
        Boolean claimed =
            connection.claim(
                service, () -> current() && phase.compareAndSet(Phase.CLAIMING, Phase.ADMITTED));
        return Boolean.TRUE.equals(claimed) && current();
      } finally {
        phase.compareAndSet(Phase.CLAIMING, Phase.FAILED);
      }
    }

    public boolean mayHaveSubmitted() {
      return mayHaveSubmitted.get();
    }

    private void cancel() {
      phase.set(Phase.CANCELLED);
    }

    @Override
    public void close() {
      cancel();
      active.compareAndSet(this, null);
    }
  }
}
