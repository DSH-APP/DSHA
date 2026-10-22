package com.deepseekharness.app.util;

import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.core.RuntimeSnapshot;

/** 授权绑定本次运行；撤销后，旧弹窗结果也不能重新授权。 */
public final class ScreenSessionGrant {
  private long generation = -1, revision;
  private boolean paused;
  private RuntimeSnapshot.Epoch runtime;
  private String bridgeRunId = "";
  private long policyRevision = -1, authorityEpoch = -1;

  public synchronized boolean paused() {
    return paused;
  }

  public synchronized void setPaused(boolean value) {
    paused = value;
    revoke();
  }

  public synchronized long revision() {
    return revision;
  }

  public synchronized boolean allowed(long current) {
    return !paused && current > 0 && generation == current;
  }

  public synchronized boolean accept(long current, long observedRevision) {
    if (paused || current <= 0 || observedRevision != revision) return false;
    clearTypedIdentity();
    generation = current;
    return true;
  }

  /** Remembered permission is specific to the complete running instance, bridge and local policy. */
  public synchronized boolean allowed(CapabilityRequest request) {
    return !paused
        && request != null
        && "screen".equals(request.capability)
        && runtime != null
        && runtime.equals(request.runtime)
        && request.installInstanceId.equals(runtime.ownerId)
        && bridgeRunId.equals(request.bridgeRunId)
        && policyRevision == request.policyRevision
        && authorityEpoch == request.authorityEpoch;
  }

  /**
   * The broker/adapter supplies currentAndActive after checking its live fence and actual target.
   * A policy ALLOW decision does not bypass any of those checks or grant an Android permission.
   */
  public synchronized boolean completeConfirmation(
      boolean accepted,
      CapabilityRequest request,
      RuntimeSnapshot.Epoch currentRuntime,
      String currentBridgeRunId,
      long observedGrantRevision,
      long currentPolicyRevision,
      boolean currentAndActive,
      boolean remember) {
    if (paused
        || !accepted
        || !currentAndActive
        || request == null
        || currentRuntime == null
        || !"screen".equals(request.capability)
        || request.runtime.generation <= 0
        || request.runtime.instanceId.isEmpty()
        || !request.runtime.equals(currentRuntime)
        || !request.installInstanceId.equals(currentRuntime.ownerId)
        || !request.bridgeRunId.equals(currentBridgeRunId)
        || observedGrantRevision != revision
        || request.policyRevision != currentPolicyRevision) return false;
    if (remember) {
      runtime = request.runtime;
      bridgeRunId = request.bridgeRunId;
      policyRevision = request.policyRevision;
      authorityEpoch = request.authorityEpoch;
      generation = request.runtime.generation;
    }
    return true;
  }

  /** Both one-shot sensitive confirmation and remembered grants have the same stale-result gate. */
  public synchronized boolean completeConfirmation(
      boolean accepted,
      long observedGeneration,
      long currentGeneration,
      long observedRevision,
      boolean sessionActive,
      boolean remember) {
    if (paused
        || !accepted
        || !sessionActive
        || observedGeneration <= 0
        || observedGeneration != currentGeneration
        || observedRevision != revision) return false;
    if (remember) {
      clearTypedIdentity();
      generation = currentGeneration;
    }
    return true;
  }

  public synchronized void revoke() {
    generation = -1;
    clearTypedIdentity();
    revision++;
  }

  private void clearTypedIdentity() {
    runtime = null;
    bridgeRunId = "";
    policyRevision = authorityEpoch = -1;
  }
}
