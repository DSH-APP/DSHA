package com.deepseekharness.app.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AssistantSessionStateTest {
  @Test
  public void inboxReceiptNeverProvesCompletion() {
    var state = new AssistantSessionState();
    state.observed(false, "", "session-with-full-id");
    state.sending();
    state.accepted();
    assertFalse(state.terminalKnown());
    assertEquals(AssistantSessionState.Phase.RUNNING, state.phase());
  }

  @Test
  public void pauseInvalidatesOldIdleBeforeCancellationFinishes() {
    var state = new AssistantSessionState();
    state.observed(false, "turn-1", "task-1");
    state.pause("IME");
    assertFalse(state.terminalKnown());
    assertThrows(IllegalStateException.class, state::resume);
    state.observed(true, "turn-1", "task-1");
    assertEquals(AssistantSessionState.Phase.PAUSING, state.phase());
    state.observed(false, "turn-1", "task-1");
    state.resume();
    assertEquals("OBSERVE_REQUIRED", state.detail());
  }

  @Test
  public void unknownMutationDoesNotAllowReplay() {
    var state = new AssistantSessionState();
    state.observed(false, "1", "task");
    state.sending();
    state.unknown("SEND_UNKNOWN");
    assertThrows(IllegalStateException.class, state::sending);
    assertThrows(IllegalStateException.class, state::resume);
  }

  @Test
  public void stopRequiresFreshIdleAndExplicitNewTask() {
    var state = new AssistantSessionState();
    state.observed(true, "1", "task");
    state.stop();
    assertThrows(IllegalStateException.class, state::newTask);
    state.observed(false, "1", "task");
    assertEquals(AssistantSessionState.Phase.STOPPED, state.phase());
    assertThrows(IllegalStateException.class, state::resume);
    state.newTask();
    state.sending();
    assertEquals(AssistantSessionState.Phase.SENDING, state.phase());
  }

  @Test
  public void disconnectedBindingCannotBeRevivedByLateStatus() {
    var state = new AssistantSessionState();
    state.observed(true, "original-turn", "original-task");
    state.disconnected();
    state.observed(false, "late-other-turn", "late-other-task");
    assertEquals(AssistantSessionState.Phase.DISCONNECTED, state.phase());
    assertEquals("original-turn", state.turnId());
    assertThrows(IllegalStateException.class, state::newTask);
  }

  @Test
  public void definitePreAdmissionRejectionRetainsAnEditableIdleTask() {
    var state = new AssistantSessionState();
    state.observed(false, "1", "task");
    state.sending();
    state.rejected("MODEL_REQUIRED");
    assertTrue(state.terminalKnown());
    assertTrue(state.paused());
    assertEquals("MODEL_REQUIRED", state.detail());
    state.newTask();
    state.sending();
    assertFalse(state.terminalKnown());
  }
}
