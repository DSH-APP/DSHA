package com.deepseekharness.app.assistant;

import java.io.IOException;
import org.json.JSONObject;

/** Mutating calls are sent once. A transport exception leaves their result unknown. */
public interface AssistantBackend {
  /** Host explicitly rejected before inbox admission; safe to edit the retained draft. */
  final class Rejected extends IOException {
    public Rejected(String code) {
      super(code);
    }
  }

  JSONObject call(String method, JSONObject args) throws IOException;

  boolean current();

  default boolean ownsControlBinding(
      com.deepseekharness.app.control.ControlTaskAuthority.Binding value) {
    return false;
  }

  void returnConversation(AssistantBinding binding) throws IOException;
}
