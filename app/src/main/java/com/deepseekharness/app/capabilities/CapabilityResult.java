package com.deepseekharness.app.capabilities;

import java.util.Objects;

/** Common execution facts. Transport-specific output/exit/cleanup evidence stays with its adapter. */
public final class CapabilityResult {
  public enum State {
    NOT_SENT,
    REJECTED,
    COMPLETED,
    PARTIAL,
    UNKNOWN
  }

  public final State state;
  public final String code, detail;

  private CapabilityResult(State state, String code, String detail) {
    this.state = Objects.requireNonNull(state, "state");
    if (code == null || code.isEmpty() || code.length() > 128)
      throw new IllegalArgumentException("INVALID_CAPABILITY_RESULT_CODE");
    this.code = code;
    this.detail = detail == null ? "" : detail;
  }

  public static CapabilityResult notSent(String code, String detail) {
    return new CapabilityResult(State.NOT_SENT, code, detail);
  }

  public static CapabilityResult rejected(String code, String detail) {
    return new CapabilityResult(State.REJECTED, code, detail);
  }

  public static CapabilityResult completed(String code, String detail) {
    return new CapabilityResult(State.COMPLETED, code, detail);
  }

  public static CapabilityResult partial(String code, String detail) {
    return new CapabilityResult(State.PARTIAL, code, detail);
  }

  public static CapabilityResult unknown(String code, String detail) {
    return new CapabilityResult(State.UNKNOWN, code, detail);
  }

  public boolean executionMayHaveOccurred() {
    return state == State.COMPLETED || state == State.PARTIAL || state == State.UNKNOWN;
  }

  /** Eligibility only; callers still need a fresh authorization and must choose any retry explicitly. */
  public boolean mayTryAlternateTransport() {
    return state == State.NOT_SENT;
  }
}
