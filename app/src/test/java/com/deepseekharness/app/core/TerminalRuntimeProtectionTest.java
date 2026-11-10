package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class TerminalRuntimeProtectionTest {
  private final AtomicLong now = new AtomicLong(100);
  private final TerminalRuntimeProtection protection = new TerminalRuntimeProtection(now::get);
  private final Object service = new Object();
  private final AtomicInteger ready = new AtomicInteger(), rejected = new AtomicInteger();
  private final TerminalRuntimeProtection.Admission admission =
      new TerminalRuntimeProtection.Admission() {
        @Override
        public void onReady(TerminalRuntimeProtection.Ticket ticket) {
          ready.incrementAndGet();
        }

        @Override
        public void onRejected(TerminalRuntimeProtection.Ticket ticket, String reason) {
          rejected.incrementAndGet();
        }
      };

  private TerminalRuntimeProtection.Ticket prepare() {
    return protection.prepare("owner", TerminalRuntimeProtection.Kind.PTY, admission);
  }

  private TerminalRuntimeProtection.Ticket promoted() {
    var ticket = prepare();
    assertTrue(protection.promoted(ticket.id(), service, () -> true));
    return ticket;
  }

  @Test
  public void creationCannotBeginBeforeRealPromotion() {
    var ticket = prepare();
    assertEquals(0, ready.get());
    assertEquals(TerminalRuntimeProtection.State.PREPARING, protection.snapshot().state);
    assertThrows(
        IOException.class,
        () -> protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY));
    assertEquals(1, rejected.get());
    assertFalse(protection.hasDemand());
    assertFalse(protection.promoted(ticket.id(), service, () -> true));
  }

  @Test
  public void exactTicketEpochKindAndSingleUseAreRequired() throws Exception {
    var ticket = promoted();
    assertEquals(1, ready.get());
    assertThrows(
        IOException.class,
        () -> protection.beginCreate(ticket, "other", TerminalRuntimeProtection.Kind.PTY));
    assertThrows(
        IOException.class,
        () -> protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.SIMPLE));
    var otherProtection = new TerminalRuntimeProtection(now::get);
    assertThrows(
        IOException.class,
        () -> otherProtection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY));
    protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY);
    assertThrows(
        IOException.class,
        () -> protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY));
    assertFalse(ticket.cancel());
    protection.finishCreate(ticket);
    assertFalse(protection.hasDemand());
  }

  @Test
  public void promotionCancellationAndLateCallbackCannotResurrectTicket() {
    var ticket = prepare();
    assertTrue(ticket.cancel());
    assertEquals(1, rejected.get());
    assertFalse(ticket.cancel());
    assertFalse(protection.promoted(ticket.id(), service, () -> true));
    assertEquals(0, ready.get());
    assertEquals(TerminalRuntimeProtection.State.RELEASED, ticket.state());
  }

  @Test
  public void promotionTimeoutRetiresOnlyThatPreparation() {
    var old = prepare();
    now.addAndGet(TerminalRuntimeProtection.PROMOTION_TIMEOUT_MS);
    var fresh = prepare();
    protection.expire(old);
    assertEquals(1, rejected.get());
    assertFalse(protection.promoted(old.id(), service, () -> true));
    assertTrue(protection.promoted(fresh.id(), service, () -> true));
    assertEquals(1, protection.snapshot().preparing);
    assertTrue(fresh.cancel());
  }

  @Test
  public void promotedPreparationHasBoundedCreateDeadline() {
    var ticket = promoted();
    now.addAndGet(TerminalRuntimeProtection.PREPARATION_TIMEOUT_MS);
    protection.expire(ticket);
    assertFalse(protection.hasDemand());
    assertThrows(
        IOException.class,
        () -> protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY));
  }

  @Test
  public void verifiedReleaseBeforeCreateReturnsStillKeepsPreparation() throws Exception {
    var ticket = promoted();
    protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY);
    protection.retain(ticket);
    protection.releaseVerified(ticket);
    assertTrue(protection.hasDemand());
    assertEquals(1, protection.snapshot().creating);
    assertEquals(0, protection.snapshot().held);
    protection.finishCreate(ticket);
    assertFalse(protection.hasDemand());
  }

  @Test
  public void unpublishedForkAndUnconfirmedCleanupKeepHoldUntilProof() throws Exception {
    var ticket = promoted();
    protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY);
    protection.retain(ticket);
    // 创建抛错但 fork/pending 尚未核验；没有可见标签也必须继续保护。
    protection.finishCreate(ticket);
    assertEquals(1, protection.snapshot().held);
    assertTrue(protection.needsService(service));
    assertFalse(ticket.cancel());
    now.addAndGet(TerminalRuntimeProtection.PREPARATION_TIMEOUT_MS * 2);
    protection.expire(ticket);
    assertTrue(protection.hasDemand());
    protection.releaseVerified(ticket);
    assertFalse(protection.hasDemand());
    protection.releaseVerified(ticket);
    assertFalse(protection.hasDemand());
  }

  @Test
  public void serviceLossBeforeCreateRefusesWithoutFork() {
    var ticket = promoted();
    protection.serviceDestroyed(service);
    assertFalse(protection.hasDemand());
    assertThrows(
        IOException.class,
        () -> protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY));
    assertEquals("TERMINAL_FOREGROUND_SERVICE_LOST", ticket.error());
  }

  @Test
  public void serviceLossWhileCreatingPreservesExactPendingHold() throws Exception {
    var ticket = promoted();
    protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY);
    protection.retain(ticket);
    protection.serviceDestroyed(service);
    assertEquals(TerminalRuntimeProtection.State.LOST, protection.snapshot().state);
    assertEquals(1, protection.snapshot().creating);
    assertThrows(IOException.class, () -> protection.requireHeld(ticket));
    protection.finishCreate(ticket);
    assertEquals(1, protection.snapshot().held);
    assertFalse(protection.needsService(service));
    protection.releaseVerified(ticket);
    assertEquals(TerminalRuntimeProtection.State.RELEASED, protection.snapshot().state);
  }

  @Test
  public void serviceLossOfRunningOrClosingHoldNeverRestartsOrReleasesIt() throws Exception {
    var ticket = promoted();
    protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY);
    protection.retain(ticket);
    protection.finishCreate(ticket);
    protection.serviceDestroyed(new Object());
    assertEquals(TerminalRuntimeProtection.State.ACTIVE, protection.snapshot().state);
    protection.serviceDestroyed(service);
    assertEquals(TerminalRuntimeProtection.State.LOST, ticket.state());
    assertEquals(1, protection.snapshot().held);
    assertFalse(protection.promoted(ticket.id(), new Object(), () -> true));
    assertFalse(ticket.cancel());
    protection.releaseVerified(ticket);
    assertFalse(protection.hasDemand());
  }

  @Test
  public void serviceLivenessIsRecheckedAtExactCreateGuard() {
    var ticket = prepare();
    AtomicBoolean alive = new AtomicBoolean(true);
    assertTrue(protection.promoted(ticket.id(), service, alive::get));
    alive.set(false);
    assertEquals(TerminalRuntimeProtection.State.LOST, protection.snapshot().state);
    assertThrows(
        IOException.class,
        () -> protection.beginCreate(ticket, "owner", TerminalRuntimeProtection.Kind.PTY));
    assertFalse(protection.hasDemand());
  }

  @Test
  public void closingOneSessionDoesNotRetireOtherTerminalOrPreparation() throws Exception {
    var first = promoted();
    protection.beginCreate(first, "owner", TerminalRuntimeProtection.Kind.PTY);
    protection.retain(first);
    protection.finishCreate(first);
    var second = prepare();
    protection.releaseVerified(first);
    assertTrue(protection.needsService(service));
    assertEquals(1, protection.snapshot().preparing);
    second.cancel();
    assertFalse(protection.needsService(service));
  }

  @Test
  public void newVisibleRequestCannotLaunderLostOldSessionProtection() throws Exception {
    var old = promoted();
    protection.beginCreate(old, "owner", TerminalRuntimeProtection.Kind.PTY);
    protection.retain(old);
    protection.finishCreate(old);
    protection.serviceDestroyed(service);
    var fresh = prepare();
    Object newService = new Object();
    assertTrue(protection.promoted(fresh.id(), newService, () -> true));
    assertTrue(protection.isProtected(fresh));
    assertFalse(protection.isProtected(old));
    assertEquals(TerminalRuntimeProtection.State.LOST, protection.snapshot().state);
    fresh.cancel();
    assertFalse(protection.needsService(newService));
    assertTrue(protection.hasDemand());
    protection.releaseVerified(old);
  }

  @Test
  public void throwingObserverOrAdmissionCannotBreakRetention() {
    protection.addObserver(
        () -> {
          throw new IllegalStateException("stale UI");
        });
    var ticket =
        protection.prepare(
            "owner",
            TerminalRuntimeProtection.Kind.PTY,
            new TerminalRuntimeProtection.Admission() {
              @Override
              public void onReady(TerminalRuntimeProtection.Ticket ticket) {
                throw new IllegalStateException("gone UI");
              }

              @Override
              public void onRejected(TerminalRuntimeProtection.Ticket ticket, String reason) {}
            });
    assertTrue(protection.promoted(ticket.id(), service, () -> true));
    assertFalse(protection.hasDemand());
  }
}
