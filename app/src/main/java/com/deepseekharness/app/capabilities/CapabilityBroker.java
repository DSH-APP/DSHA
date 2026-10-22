package com.deepseekharness.app.capabilities;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Application-owned authority fences and a single native confirmation slot. There is no queue.
 * No adapter, platform call, UI callback or I/O runs while the broker lock is held.
 */
public final class CapabilityBroker {
  private final LongSupplier monotonicMillis;
  private final Map<String, Long> epochs = new HashMap<>();
  // Entries disappear once nobody can submit that request object again; no unbounded run history.
  private final Set<CapabilityRequest> claimed =
      Collections.newSetFromMap(new WeakHashMap<CapabilityRequest, Boolean>());
  private final Set<CapabilityRequest> authorized =
      Collections.newSetFromMap(new WeakHashMap<CapabilityRequest, Boolean>());
  private final Set<CapabilityRequest> canceled =
      Collections.newSetFromMap(new WeakHashMap<CapabilityRequest, Boolean>());
  private long sequence, globalEpoch;
  private Confirmation pending;

  public CapabilityBroker(LongSupplier monotonicMillis) {
    this.monotonicMillis = java.util.Objects.requireNonNull(monotonicMillis, "clock");
  }

  public long nowMillis() {
    return monotonicMillis.getAsLong();
  }

  public synchronized long epoch(String capability) {
    return epochs.getOrDefault(capability, globalEpoch);
  }

  /** Runtime/target/system availability must additionally be revalidated by the executing adapter. */
  public synchronized boolean current(CapabilityRequest request) {
    return request != null
        && !canceled.contains(request)
        && request.runtime.generation > 0
        && !request.runtime.instanceId.isEmpty()
        && request.installInstanceId.equals(request.runtime.ownerId)
        && request.authorityEpoch == epoch(request.capability)
        && nowMillis() < request.deadlineElapsedRealtimeMillis;
  }

  public synchronized Confirmation beginConfirmation(CapabilityRequest request) {
    if (pending != null || !current(request) || claimed.contains(request)) return null;
    authorized.remove(request);
    pending = new Confirmation(request);
    return pending;
  }

  public synchronized boolean pending(Confirmation confirmation) {
    return confirmation != null
        && pending == confirmation
        && current(confirmation.request)
        && confirmation.done.getCount() != 0;
  }

  public synchronized boolean resolve(Confirmation confirmation, boolean allow) {
    if (!pending(confirmation)) return false;
    if (allow) authorized.add(confirmation.request);
    else cancelAuthority(confirmation.request);
    confirmation.answer = allow;
    confirmation.done.countDown();
    return true;
  }

  public synchronized void finish(Confirmation confirmation) {
    if (pending != confirmation || confirmation == null) return;
    if (!Boolean.TRUE.equals(confirmation.answer)) cancelAuthority(confirmation.request);
    confirmation.done.countDown();
    pending = null;
  }

  public synchronized void revoke(String capability) {
    epochs.put(capability, nextEpoch());
    if (pending != null && pending.request.capability.equals(capability)) finish(pending);
  }

  public synchronized void revokeAll() {
    globalEpoch = nextEpoch();
    epochs.clear();
    if (pending != null) finish(pending);
  }

  private long nextEpoch() {
    // Exhaustion is impossible in practice, but never wrap into an old valid authority epoch.
    if (sequence == Long.MAX_VALUE) throw new IllegalStateException("AUTHORITY_EPOCH_EXHAUSTED");
    return ++sequence;
  }

  /** Called only after the native orchestrator has evaluated current local policy/system consent. */
  public synchronized boolean authorize(CapabilityRequest request) {
    if (!current(request) || claimed.contains(request)) return false;
    authorized.add(request);
    return true;
  }

  /** For adapter validation before its one-use claim; after claiming use the permit's live fence. */
  public synchronized boolean authorized(CapabilityRequest request) {
    return current(request) && authorized.contains(request) && !claimed.contains(request);
  }

  public synchronized void cancel(CapabilityRequest request) {
    if (request == null) return;
    cancelAuthority(request);
    if (pending != null && pending.request == request) finish(pending);
  }

  private void cancelAuthority(CapabilityRequest request) {
    authorized.remove(request);
    canceled.add(request);
  }

  /**
   * One-use dispatch claim. Once returned, a side effect may have occurred even if revocation races
   * the subsequent platform send. A caller cannot then report NOT_SENT merely because it lost a reply.
   */
  public synchronized DispatchPermit claimDispatch(CapabilityRequest request) {
    if (!current(request) || !authorized.remove(request) || !claimed.add(request)) return null;
    return new DispatchPermit(this, request);
  }

  public boolean isCurrent(DispatchPermit permit) {
    return permit != null && permit.owner == this && current(permit.request);
  }

  public static final class DispatchPermit {
    private final CapabilityBroker owner;
    public final CapabilityRequest request;

    private DispatchPermit(CapabilityBroker owner, CapabilityRequest request) {
      this.owner = owner;
      this.request = request;
    }

    public boolean isCurrent() {
      return owner.isCurrent(this);
    }
  }

  public static final class Confirmation {
    public final CapabilityRequest request;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile Boolean answer;

    private Confirmation(CapabilityRequest request) {
      this.request = request;
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      return done.await(timeout, unit) && Boolean.TRUE.equals(answer);
    }

    /** null means canceled/unanswered, never a user rejection. */
    public Boolean answer() {
      return answer;
    }
  }
}
