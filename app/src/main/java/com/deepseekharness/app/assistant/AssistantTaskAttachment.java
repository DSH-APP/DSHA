package com.deepseekharness.app.assistant;

import java.io.IOException;
import org.json.JSONObject;

/** A tool request may attach only to the Host's exact, currently executing conversation. */
final class AssistantTaskAttachment {
  private AssistantTaskAttachment() {}

  static void verify(AssistantBinding binding, String turnId, JSONObject status)
      throws IOException {
    if (turnId == null
        || turnId.isBlank()
        || status == null
        || !binding.hostInstanceId().equals(status.optString("hostInstanceId"))
        || !binding.sessionId().equals(status.optString("sessionId"))
        || !turnId.equals(status.optString("turnId"))
        || !Boolean.TRUE.equals(status.opt("turnActive"))
        || !Boolean.TRUE.equals(status.opt("running")))
      throw new IOException("ASSISTANT_TASK_CHANGED");
  }

  static boolean mayReplace(
      AssistantSessionController.Snapshot previous, AssistantBinding requested) {
    return previous == null
        || previous.binding().equals(requested)
        || (previous.terminalKnown()
            && !previous.paused()
            && previous.phase() == AssistantSessionState.Phase.IDLE);
  }
}
