package com.deepseekharness.app.screen;

import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.capabilities.CapabilityBroker;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.capabilities.WindowTargetEvidence;
import com.deepseekharness.app.util.ScreenConnectionEpoch;
import com.deepseekharness.app.util.ScreenTarget;
import java.util.function.BooleanSupplier;

/** 跟随异步请求传递的不可变身份；不从线程局部变量取得授权。 */
public final class ScreenRequestContext {
  private final CapabilityBroker broker;
  private final CapabilityRequest request;
  private final BooleanSupplier authority;
  private final ScreenConnectionEpoch<DshaAccessibilityService> connection;
  private final ScreenConnectionEpoch.Lease<DshaAccessibilityService> lease;
  private final ScreenTarget target;
  private final boolean boundedPhysicalRequest;
  private volatile CapabilityBroker.DispatchPermit permit;
  private final java.util.concurrent.atomic.AtomicBoolean cancelled =
      new java.util.concurrent.atomic.AtomicBoolean();

  private enum DispatchPhase {
    READY,
    CLAIMING,
    CLAIMED,
    ADMITTED,
    FAILED,
    CANCELLED
  }

  private final java.util.concurrent.atomic.AtomicReference<DispatchPhase> dispatch =
      new java.util.concurrent.atomic.AtomicReference<>(DispatchPhase.READY);

  public ScreenRequestContext(
      CapabilityBroker broker,
      CapabilityRequest request,
      BooleanSupplier authority,
      ScreenConnectionEpoch<DshaAccessibilityService> connection) {
    this(broker, request, authority, connection, false);
  }

  ScreenRequestContext(
      CapabilityBroker broker,
      CapabilityRequest request,
      BooleanSupplier authority,
      ScreenConnectionEpoch<DshaAccessibilityService> connection,
      boolean boundedPhysicalRequest) {
    this.broker = broker;
    this.request = request;
    this.authority = authority;
    this.connection = connection;
    this.boundedPhysicalRequest = boundedPhysicalRequest;
    lease = connection.snapshot();
    target =
        request != null && request.target instanceof WindowTargetEvidence window
            ? new ScreenTarget(window.displayId(), window.windowId(), window.packageName())
            : null;
  }

  public CapabilityRequest request() {
    return request;
  }

  public DshaAccessibilityService service() {
    return lease == null ? null : lease.service;
  }

  public long serviceEpoch() {
    return lease == null ? -1 : lease.epoch;
  }

  public ScreenTarget target() {
    return target;
  }

  public long nowMillis() {
    return broker.nowMillis();
  }

  public String ownerIdentity() {
    return request.installInstanceId
        + '\0'
        + request.bridgeRunId
        + '\0'
        + request.runtime.generation
        + '\0'
        + request.runtime.instanceId
        + '\0'
        + request.authorityEpoch
        + '\0'
        + request.policyRevision;
  }

  public boolean current() {
    try {
      return broker != null
          && !cancelled.get()
          && request != null
          && "screen".equals(request.capability)
          && target != null
          && target.windowId >= 0
          && connection.current(lease)
          && (permit == null ? broker.authorized(request) : permit.isCurrent())
          && authority != null
          && authority.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  public void validateRoot(int displayId, AccessibilityNodeInfo root) {
    if (!current() || !target.matches(ScreenTargetResolver.identity(displayId, root)))
      throw new DshaAccessibilityService.TargetChangedException();
  }

  public void validateCurrentTarget() {
    if (!current()) throw new DshaAccessibilityService.TargetChangedException();
    try (var resolved = ScreenTargetResolver.resolve(service(), target.displayId)) {
      validateRoot(target.displayId, resolved.root);
    }
  }

  public com.deepseekharness.app.util.ScreenWindowRegion currentRegion() {
    var region = currentRegionIfKnown();
    if (region == null) throw new IllegalStateException("SCREEN_WINDOW_GEOMETRY_UNAVAILABLE");
    return region;
  }

  /** A readable current target need not expose coordinate geometry. Never invent its rectangle. */
  public com.deepseekharness.app.util.ScreenWindowRegion currentRegionIfKnown() {
    if (!current()) throw new DshaAccessibilityService.TargetChangedException();
    try (var resolved = ScreenTargetResolver.resolve(service(), target.displayId)) {
      validateRoot(target.displayId, resolved.root);
      return resolved.region;
    }
  }

  /** 领取就是可能提交边界；领取后撤销不能再返回未执行。 */
  public synchronized boolean claimDispatch() {
    if (!current()) return false;
    if (permit != null) return permit.isCurrent();
    if (!boundedPhysicalRequest) {
      permit = connection.claim(lease, () -> broker.claimDispatch(request));
      return permit != null;
    }
    if (!dispatch.compareAndSet(DispatchPhase.READY, DispatchPhase.CLAIMING)) return false;
    try {
      if (!current()) return false;
      permit = connection.claim(lease, () -> broker.claimDispatch(request));
      if (permit == null
          || !dispatch.compareAndSet(DispatchPhase.CLAIMING, DispatchPhase.CLAIMED)
          || !current()) return false;
      // This final local admission and cancellation have one atomic owner. A late broker permit
      // alone cannot authorize a platform action after cancellation won.
      return dispatch.compareAndSet(DispatchPhase.CLAIMED, DispatchPhase.ADMITTED);
    } finally {
      if (permit == null) dispatch.compareAndSet(DispatchPhase.CLAIMING, DispatchPhase.FAILED);
    }
  }

  /** Never waits for the platform or the claim monitor; a racing claim is conservatively unknown. */
  boolean cancelAndMayHaveSubmitted() {
    DispatchPhase state;
    do {
      state = dispatch.get();
    } while (state != DispatchPhase.ADMITTED
        && state != DispatchPhase.CANCELLED
        && !dispatch.compareAndSet(state, DispatchPhase.CANCELLED));
    cancelled.set(true);
    if (broker != null && request != null) broker.cancel(request);
    return state == DispatchPhase.CLAIMING || state == DispatchPhase.CLAIMED || permit != null;
  }

  public boolean markSubmittedOnce() {
    return claimDispatch();
  }

  public boolean submitted() {
    return permit != null;
  }
}
