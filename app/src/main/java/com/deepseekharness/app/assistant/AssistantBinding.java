package com.deepseekharness.app.assistant;

/** A conversation address, never a shortened display label or the transport session. */
public record AssistantBinding(
    String hostInstanceId, String sessionId, String connectionEpoch, long generation) {
  public AssistantBinding {
    check(hostInstanceId);
    check(sessionId);
    check(connectionEpoch);
    if (generation < 1) throw new IllegalArgumentException("ASSISTANT_BINDING_INVALID");
  }

  private static void check(String value) {
    if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("ASSISTANT_ID_INVALID");
  }
}
