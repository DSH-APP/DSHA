package com.deepseekharness.app.control;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ControlTaskAuthorityTest {
  @Test
  public void actionsRequireTheSameWindowAsTheLatestSuccessfulObservation() {
    var authority = new ControlTaskAuthority();
    var binding = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "session", "1");
    var lease = authority.begin(binding);
    var github = new com.deepseekharness.app.util.ScreenTarget(0, 12, "com.github.android");
    var dsha = new com.deepseekharness.app.util.ScreenTarget(0, 13, "io.github.sunsetrne.dsha");
    assertFalse(authority.actionAllowed(lease, github));
    assertTrue(authority.observed(lease, authority.beginObservation(lease), github));
    assertTrue(authority.actionAllowed(lease, github));
    assertFalse(authority.actionAllowed(lease, dsha));
    assertFalse(
        authority.actionAllowed(
            lease, new com.deepseekharness.app.util.ScreenTarget(0, 13, "com.github.android")));
    assertFalse(
        authority.actionAllowed(
            lease, new com.deepseekharness.app.util.ScreenTarget(1, 12, "com.github.android")));
    assertTrue(authority.observed(lease, authority.beginObservation(lease), dsha));
    assertFalse(authority.actionAllowed(lease, github));
    assertTrue(authority.actionAllowed(lease, dsha));
    assertFalse(authority.checkActionTarget(lease, github));
    assertFalse(authority.actionAllowed(lease, dsha));
    assertTrue(authority.observed(lease, authority.beginObservation(lease), dsha));
    authority.requireObservation(binding);
    var next = authority.lease(binding);
    assertFalse(authority.actionAllowed(next, dsha));
    assertFalse(authority.observed(lease, authority.beginObservation(lease), github));
    assertFalse(authority.actionAllowed(next, github));
  }

  @Test
  public void olderObservationCannotOverwriteNewerOrRestoreEvidenceAfterFailure() {
    var authority = new ControlTaskAuthority();
    var lease = authority.begin(binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "s", "1"));
    var github = new com.deepseekharness.app.util.ScreenTarget(0, 12, "com.github.android");
    var dsha = new com.deepseekharness.app.util.ScreenTarget(0, 13, "io.github.sunsetrne.dsha");
    long old = authority.beginObservation(lease);
    long newer = authority.beginObservation(lease);
    assertTrue(authority.observed(lease, newer, dsha));
    assertFalse(authority.observed(lease, old, github));
    assertTrue(authority.actionAllowed(lease, dsha));
    long beforeFailure = authority.beginObservation(lease);
    authority.beginObservation(lease);
    assertFalse(authority.observed(lease, beforeFailure, github));
    assertFalse(authority.actionAllowed(lease, dsha));
  }

  @Test
  public void asynchronousPauseRequiresTheExactCurrentLeaseWithoutPausingItsSuccessor() {
    var authority = new ControlTaskAuthority();
    var first = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "same-session", "17");
    var old = authority.begin(first);
    authority.completeLocal(first);
    var next =
        authority.begin(binding(first.owner(), first.connection(), first.taskSessionId(), "18"));
    assertNull(authority.pauseCurrent(old, "OLD_STATUS_FAILURE"));
    assertFalse(authority.snapshot().paused());
    assertTrue(authority.current(next));
    assertEquals(next.binding(), authority.pauseCurrent(next, "CURRENT_STATUS_FAILURE"));
    assertTrue(authority.snapshot().paused());
    assertFalse(authority.current(next));
    authority.resume();
    var resumed = authority.lease(next.binding());
    assertNull(authority.pauseCurrent(next, "STALE_REVISION"));
    assertTrue(authority.current(resumed));
  }

  @Test
  public void removingOwnedUiRequiresNewObservationWithoutGrantingResume() {
    var authority = new ControlTaskAuthority();
    var task = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "full-session", "7");
    var old = authority.begin(task);
    assertTrue(authority.observed(old));
    authority.requireObservation(task);
    assertFalse(authority.current(old));
    var current = authority.lease(task);
    assertFalse(authority.actionAllowed(current));
    assertThrows(
        IllegalStateException.class,
        () ->
            authority.requireObservation(
                binding(task.owner(), task.connection(), "another-session", "7")));
    assertTrue(authority.current(current));
    authority.pause("USER_PAUSED");
    assertThrows(IllegalStateException.class, () -> authority.requireObservation(task));
    assertTrue(authority.snapshot().paused());
  }

  private static ControlTaskAuthority.Binding binding(
      ControlTaskAuthority.Owner owner, String connection, String task, String turn) {
    return new ControlTaskAuthority.Binding(owner, connection, task, turn);
  }

  @Test
  public void onePhysicalScreenAcrossIndependentLocalConversations() {
    var authority = new ControlTaskAuthority();
    var local =
        authority.begin(
            binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge:17", "local-session", "1"));
    assertFalse(authority.actionAllowed(local));
    assertTrue(authority.observed(local));
    assertTrue(authority.actionAllowed(local));
    assertThrows(
        IllegalStateException.class,
        () ->
            authority.begin(
                binding(
                    ControlTaskAuthority.Owner.LOCAL_DSH, "bridge:17", "another-session", "1")));
    authority.stop("LOCAL_DONE");
    assertFalse(authority.current(local));
    var another =
        authority.begin(
            binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge:17", "another-session", "1"));
    assertTrue(authority.current(another));
    assertFalse(authority.actionAllowed(another));
  }

  @Test
  public void pauseFencesImmediatelyAndResumeCannotReuseObservation() {
    var authority = new ControlTaskAuthority();
    var binding = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge:1", "session-full", "7");
    var old = authority.begin(binding);
    authority.observed(old);
    assertEquals(binding, authority.pause("LOCAL_USER_PAUSE"));
    assertFalse(authority.current(old));
    assertFalse(authority.actionAllowed(old));
    assertThrows(IllegalStateException.class, () -> authority.begin(binding));
    authority.resume();
    assertFalse(authority.current(old));
    var resumed = authority.lease(binding);
    assertFalse(authority.actionAllowed(resumed));
    assertTrue(authority.observed(resumed));
    assertTrue(authority.actionAllowed(resumed));
  }

  @Test
  public void taskStopAndNewConnectionCannotClearLocalPause() {
    var authority = new ControlTaskAuthority();
    authority.begin(binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge:1", "session", "7"));
    authority.pause("LOCAL_USER_PAUSE");
    authority.stop("LOCAL_STOP");
    assertTrue(authority.snapshot().paused());
    assertEquals("LOCAL_USER_PAUSE", authority.snapshot().reason());
    assertThrows(
        IllegalStateException.class,
        () ->
            authority.begin(
                binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge:2", "session", "7")));
    assertNull(authority.snapshot().binding());
  }

  @Test
  public void fullConnectionConversationAndTurnAreDistinctEvenWithSameSuffix() {
    var authority = new ControlTaskAuthority();
    var full =
        binding(
            ControlTaskAuthority.Owner.LOCAL_DSH,
            "bridge-generation-A",
            "session-abc0001",
            "10001");
    var active = authority.begin(full);
    for (var wrong :
        new ControlTaskAuthority.Binding[] {
          binding(
              ControlTaskAuthority.Owner.LOCAL_DSH,
              "bridge-generation-B",
              "session-abc0001",
              "10001"),
          binding(
              ControlTaskAuthority.Owner.LOCAL_DSH,
              "bridge-generation-A",
              "session-xyz0001",
              "10001"),
          binding(
              ControlTaskAuthority.Owner.LOCAL_DSH,
              "bridge-generation-A",
              "session-abc0001",
              "20001")
        }) {
      assertThrows(IllegalStateException.class, () -> authority.lease(wrong));
      assertNotEquals(full.identity(), wrong.identity());
    }
    assertTrue(authority.current(active));
  }

  @Test
  public void stoppedLeaseCannotReuseObservationWhenSameTurnIsExplicitlyReadmitted() {
    var authority = new ControlTaskAuthority();
    var binding = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge:1", "session", "7");
    var first = authority.begin(binding);
    authority.observed(first);
    assertEquals(binding, authority.stop("DONE"));
    var next = authority.begin(binding);
    assertFalse(authority.current(first));
    assertFalse(authority.actionAllowed(next));
  }

  @Test
  public void consecutiveActualLocalTurnsRetireExactlyAndRequireNewObservation() {
    var authority = new ControlTaskAuthority();
    var a1 = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "prefix-A-same1234", "1");
    var b1 = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "prefix-B-same1234", "1");
    var a2 = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "prefix-A-same1234", "2");
    var first = authority.begin(a1);
    authority.observed(first);
    assertThrows(IllegalStateException.class, () -> authority.begin(b1));
    assertFalse(authority.completeLocal(b1));
    assertTrue(authority.current(first));
    assertTrue(authority.completeLocal(a1));
    assertFalse(authority.current(first));
    assertThrows(IllegalStateException.class, () -> authority.begin(a1));
    var second = authority.begin(a2);
    assertFalse(authority.actionAllowed(second));
    assertFalse(authority.completeLocal(a1));
    assertTrue(authority.current(second));
    assertTrue(authority.observed(second));
    assertTrue(authority.actionAllowed(second));
  }

  @Test
  public void completedLocalTurnCannotClearPauseOrRetireAnotherWebGeneration() {
    var authority = new ControlTaskAuthority();
    var local = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "session", "1");
    authority.begin(local);
    authority.pause("USER_PAUSE");
    assertTrue(authority.completeLocal(local));
    assertTrue(authority.snapshot().paused());
    assertEquals("USER_PAUSE", authority.snapshot().reason());
    authority.resume();
    var another =
        authority.begin(
            binding(ControlTaskAuthority.Owner.LOCAL_DSH, "next-bridge", "session", "1"));
    assertFalse(authority.completeLocal(local));
    assertTrue(authority.current(another));
  }

  @Test
  public void onlyActualPositiveSafeIntegerTurnsCanAcquireOrCompletePhoneControl() {
    var authority = new ControlTaskAuthority();
    for (String turn : new String[] {"0", "-1", "01", "1.0", "1e2", "turn-7", "9007199254740992"}) {
      var invalid = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "session", turn);
      assertThrows(IllegalArgumentException.class, () -> authority.begin(invalid));
      assertThrows(IllegalArgumentException.class, () -> authority.completeLocal(invalid));
    }
    var maximum =
        binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "session", "9007199254740991");
    var lease = authority.begin(maximum);
    assertTrue(authority.current(lease));
    assertTrue(authority.completeLocal(maximum));
    assertThrows(IllegalStateException.class, () -> authority.begin(maximum));
  }
}
