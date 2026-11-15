package com.deepseekharness.app.assistant;

import static org.junit.Assert.*;
import org.junit.Test;

public final class AssistantBindingTest {
  @Test
  public void fullConversationAndRunningInstanceRemainPartOfTheAddress() {
    var selected = new AssistantBinding("host", "prefix-A-same1234", "local-4-host", 4);
    assertNotEquals(selected, new AssistantBinding("host", "prefix-B-same1234", "local-4-host", 4));
    assertNotEquals(
        selected, new AssistantBinding("next-host", selected.sessionId(), "local-4-host", 4));
    assertNotEquals(
        selected, new AssistantBinding("host", selected.sessionId(), "local-5-host", 5));
    assertEquals(selected, new AssistantBinding("host", selected.sessionId(), "local-4-host", 4));
  }

  @Test
  public void invalidOrMissingAddressesCannotBecomeBindings() {
    for (String invalid : new String[] {null, "", "  ", "id\0suffix", "x".repeat(257)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AssistantBinding(invalid, "session", "epoch", 1));
      assertThrows(
          IllegalArgumentException.class, () -> new AssistantBinding("host", invalid, "epoch", 1));
      assertThrows(
          IllegalArgumentException.class,
          () -> new AssistantBinding("host", "session", invalid, 1));
    }
    assertThrows(
        IllegalArgumentException.class, () -> new AssistantBinding("host", "session", "epoch", 0));
  }
}
