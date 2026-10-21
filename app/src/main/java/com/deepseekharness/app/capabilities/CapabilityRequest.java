package com.deepseekharness.app.capabilities;

import com.deepseekharness.app.core.RuntimeSnapshot;
import java.util.Objects;
import java.util.UUID;

/** Explicit request context. It must travel with asynchronous work rather than through ThreadLocal. */
public final class CapabilityRequest {
  public final String requestId, capability, action, installInstanceId, bridgeRunId;
  public final RuntimeSnapshot.Epoch runtime;
  public final TargetEvidence target;
  public final long policyRevision, authorityEpoch, deadlineElapsedRealtimeMillis;

  public CapabilityRequest(
      String requestId,
      String capability,
      String action,
      RuntimeSnapshot.Epoch runtime,
      String installInstanceId,
      String bridgeRunId,
      TargetEvidence target,
      long policyRevision,
      long authorityEpoch,
      long deadlineElapsedRealtimeMillis) {
    this.requestId = text(requestId, 256, "INVALID_REQUEST_ID");
    this.capability = text(capability, 64, "INVALID_CAPABILITY");
    if (!capability.matches("[a-z][a-z0-9._-]*"))
      throw new IllegalArgumentException("INVALID_CAPABILITY");
    this.action = text(action, 8192, "INVALID_CAPABILITY_ACTION");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.installInstanceId = text(installInstanceId, 256, "INVALID_INSTALLATION_ID");
    this.bridgeRunId = text(bridgeRunId, 256, "INVALID_BRIDGE_RUN_ID");
    this.target = Objects.requireNonNull(target, "target");
    if (policyRevision < 0 || authorityEpoch < 0 || deadlineElapsedRealtimeMillis < 0)
      throw new IllegalArgumentException("INVALID_REQUEST_FENCE");
    this.policyRevision = policyRevision;
    this.authorityEpoch = authorityEpoch;
    this.deadlineElapsedRealtimeMillis = deadlineElapsedRealtimeMillis;
  }

  public static CapabilityRequest create(
      String capability,
      String action,
      RuntimeSnapshot.Epoch runtime,
      String installInstanceId,
      String bridgeRunId,
      TargetEvidence target,
      long policyRevision,
      long authorityEpoch,
      long deadlineElapsedRealtimeMillis) {
    return new CapabilityRequest(
        UUID.randomUUID().toString(),
        capability,
        action,
        runtime,
        installInstanceId,
        bridgeRunId,
        target,
        policyRevision,
        authorityEpoch,
        deadlineElapsedRealtimeMillis);
  }

  private static String text(String value, int limit, String error) {
    if (value == null || value.isEmpty() || value.length() > limit || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException(error);
    return value;
  }
}
