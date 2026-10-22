package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Real startup owner and generation gate; no Android service, filesystem or device execution. */
public final class WebStartProtectionTest {
  private static final class Fixture {
    final WebLifecycle lifecycle = new WebLifecycle();
    final AtomicLong clock = new AtomicLong();
    final WebStartProtection owner = new WebStartProtection(lifecycle, clock::get);
    final AtomicBoolean serviceCurrent = new AtomicBoolean(true);
    final Object service = new Object();
    final WebStartProtection.Ticket ticket = owner.begin();

    void promote() throws IOException {
      assertTrue(owner.promote(ticket.id(), service, serviceCurrent::get));
      ticket.awaitPromotion();
    }

    long admit() {
      return owner.beginStart(ticket, false);
    }

    long internalStop() {
      return owner.beginInternalStop(ticket);
    }
  }

  @Test
  public void cannotAdmitWithoutActualPromotion() {
    Fixture fixture = new Fixture();
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
    assertEquals(-1, fixture.admit());
    assertEquals(0, fixture.lifecycle.generation());
    assertFalse(fixture.lifecycle.isStarting());
  }

  @Test
  public void wrongPromotionIdCannotAdmitThePendingRequest() throws Exception {
    Fixture fixture = new Fixture();
    assertFalse(
        fixture.owner.promote(
            "different-" + fixture.ticket.id(), fixture.service, fixture.serviceCurrent::get));
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
    fixture.promote();
    assertTrue(fixture.owner.canAdmit(fixture.ticket));
  }

  @Test
  public void exactCurrentServiceCanHandOffToOneRunningGeneration() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    assertTrue(fixture.owner.canAdmit(fixture.ticket));
    assertEquals(1, fixture.admit());
    assertFalse(fixture.owner.hasPreparing());
    assertTrue(fixture.lifecycle.isStarting());
    assertTrue(fixture.lifecycle.isCurrent(1));
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
    assertEquals(-1, fixture.admit());
    assertEquals(1, fixture.lifecycle.generation());
  }

  @Test
  public void preparationTimeoutOrCloseCannotCancelAnAdmittedRuntime() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    fixture.admit();
    fixture.clock.set(WebStartProtection.PREPARATION_TIMEOUT_MS + 1);
    fixture.ticket.close();
    assertTrue(fixture.lifecycle.isStarting());
    assertTrue(fixture.lifecycle.isCurrent(1));
    assertTrue(fixture.owner.claimQueuedStart(fixture.ticket, 1));
  }

  @Test
  public void cancellationCannotBeRepromoted() {
    Fixture fixture = new Fixture();
    fixture.ticket.close();
    assertFalse(
        fixture.owner.promote(fixture.ticket.id(), fixture.service, fixture.serviceCurrent::get));
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
    assertFalse(fixture.owner.hasPreparing());
  }

  @Test
  public void explicitPromotionFailureUnblocksTheWorkerWithoutGrantingAdmission() {
    Fixture fixture = new Fixture();
    fixture.owner.reject(fixture.ticket.id(), "fixture-promotion-failed");
    IOException failure = assertThrows(IOException.class, fixture.ticket::awaitPromotion);
    assertEquals("fixture-promotion-failed", failure.getMessage());
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
  }

  @Test
  public void interruptedPromotionWaitCancelsOnlyPreparationAndPreservesInterrupt()
      throws Exception {
    Fixture fixture = new Fixture();
    CountDownLatch waiting = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              waiting.countDown();
              try {
                fixture.ticket.awaitPromotion();
                failure.set(new AssertionError("promotion must not complete"));
              } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
              }
            });
    worker.setDaemon(true);
    worker.start();
    try {
      assertTrue(waiting.await(5, TimeUnit.SECONDS));
      worker.interrupt();
      worker.join(5000);
      assertFalse(worker.isAlive());
      assertTrue(failure.get() instanceof IOException);
      assertEquals("WEB_FOREGROUND_PROMOTION_INTERRUPTED", failure.get().getMessage());
      assertTrue(interrupted.get());
      assertFalse(fixture.owner.canAdmit(fixture.ticket));
    } finally {
      worker.interrupt();
    }
  }

  @Test
  public void expiredPreflightCannotAdmit() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    fixture.clock.set(WebStartProtection.PREPARATION_TIMEOUT_MS);
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
    assertFalse(fixture.owner.hasPreparing());
    assertThrows(IOException.class, fixture.ticket::awaitPromotion);
  }

  @Test
  public void clockRollbackInvalidatesPreparation() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    fixture.clock.set(-1);
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
  }

  @Test
  public void onlyThePromotingServiceDestructionCancelsPreparation() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    fixture.owner.serviceDestroyed(new Object());
    assertTrue(fixture.owner.canAdmit(fixture.ticket));
    fixture.owner.serviceDestroyed(fixture.service);
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
  }

  @Test
  public void lostOrReplacedServiceSlotCannotReauthorizeTheOldTicket() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    assertFalse(
        fixture.owner.promote(fixture.ticket.id(), new Object(), fixture.serviceCurrent::get));
    fixture.serviceCurrent.set(false);
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
    fixture.serviceCurrent.set(true);
    assertFalse(
        fixture.owner.promote(fixture.ticket.id(), fixture.service, fixture.serviceCurrent::get));
  }

  @Test
  public void internalStopRequiresPromotedProtection() {
    Fixture fixture = new Fixture();
    assertEquals(-1, fixture.internalStop());
    assertEquals(0, fixture.lifecycle.generation());
    assertFalse(fixture.lifecycle.isStopping());
  }

  @Test
  public void safeInternalStopKeepsTheExactTicketUntilItsContinuation() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    long stopped = fixture.internalStop();
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
    assertTrue(fixture.owner.hasPreparing());
    assertFalse(fixture.owner.resumeAfterStop(fixture.ticket));
    fixture.lifecycle.finishStop(stopped);
    assertTrue(fixture.owner.resumeAfterStop(fixture.ticket));
    assertTrue(fixture.owner.canAdmit(fixture.ticket));
    fixture.admit();
    assertTrue(fixture.lifecycle.isCurrent(stopped + 1));
  }

  @Test
  public void repeatedExternalStopCancelsEvenWhenGenerationDoesNotChange() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    long stopped = fixture.internalStop();
    fixture.owner.cancelAll();
    assertEquals(stopped, fixture.lifecycle.beginStop());
    fixture.lifecycle.finishStop(stopped);
    assertFalse(fixture.owner.resumeAfterStop(fixture.ticket));
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
  }

  @Test
  public void oldTicketCannotPromoteRejectOrCloseANewerTicket() {
    Fixture fixture = new Fixture();
    fixture.ticket.close();
    WebStartProtection.Ticket next = fixture.owner.begin();
    assertNotNull(next);
    assertFalse(
        fixture.owner.promote(fixture.ticket.id(), fixture.service, fixture.serviceCurrent::get));
    fixture.owner.reject(fixture.ticket.id(), "old-timeout");
    fixture.ticket.close();
    assertTrue(fixture.owner.promote(next.id(), fixture.service, fixture.serviceCurrent::get));
    assertTrue(fixture.owner.canAdmit(next));
  }

  @Test
  public void foreignOwnerAndNullTicketsAreRejected() throws Exception {
    Fixture fixture = new Fixture(), other = new Fixture();
    fixture.promote();
    other.promote();
    assertFalse(fixture.owner.canAdmit(other.ticket));
    assertFalse(fixture.owner.canAdmit(null));
    fixture.ticket.close();
    assertFalse(fixture.owner.canAdmit(null));
  }

  @Test
  public void generationChangeBeforeFinalAdmissionRejectsTheTicket() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    fixture.lifecycle.beginStop();
    assertFalse(fixture.owner.canAdmit(fixture.ticket));
  }

  @Test
  public void handoffRechecksDeadlineEvenAfterAnEarlierSuccessfulCheck() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    assertTrue(fixture.owner.canAdmit(fixture.ticket));
    fixture.clock.set(WebStartProtection.PREPARATION_TIMEOUT_MS);
    assertEquals(-1, fixture.admit());
    assertEquals(0, fixture.lifecycle.generation());
    assertFalse(fixture.lifecycle.isStarting());
  }

  @Test
  public void newOwnerDoesNotInheritPendingRequestsOrPromotionReceipts() throws Exception {
    Fixture old = new Fixture();
    old.promote();
    WebStartProtection replacement = new WebStartProtection(new WebLifecycle(), old.clock::get);
    assertFalse(replacement.hasPreparing());
    assertFalse(replacement.matches(old.ticket.id()));
    assertFalse(replacement.canAdmit(old.ticket));
    assertFalse(replacement.promote(old.ticket.id(), old.service, old.serviceCurrent::get));
  }

  @Test
  public void oldIntentIdentityCannotMatchANewOwnersVisibleRequest() throws Exception {
    Fixture old = new Fixture();
    old.promote();
    WebStartProtection replacement = new WebStartProtection(new WebLifecycle(), old.clock::get);
    WebStartProtection.Ticket fresh = replacement.begin();
    assertNotNull(fresh);
    assertNotEquals(old.ticket.id(), fresh.id());
    assertFalse(replacement.matches(old.ticket.id()));
    assertFalse(replacement.promote(old.ticket.id(), old.service, old.serviceCurrent::get));
    assertTrue(replacement.promote(fresh.id(), new Object(), () -> true));
    assertTrue(replacement.canAdmit(fresh));
  }

  @Test
  public void newVisibleRequestAfterACompletedStopCanBePromotedAndAdmitted() throws Exception {
    WebLifecycle lifecycle = new WebLifecycle();
    long stopped = lifecycle.beginStop();
    lifecycle.finishStop(stopped);
    WebStartProtection owner = new WebStartProtection(lifecycle, () -> 0L);
    WebStartProtection.Ticket ticket = owner.begin();
    assertNotNull(ticket);
    assertTrue(owner.promote(ticket.id(), new Object(), () -> true));
    ticket.awaitPromotion();
    assertTrue(owner.canAdmit(ticket));
    assertEquals(stopped + 1, owner.beginStart(ticket, true));
  }

  @Test
  public void finalInternalStopRejectsAnExpiredTicketWithoutMutatingLifecycle() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    assertTrue(fixture.owner.canAdmit(fixture.ticket));
    fixture.clock.set(WebStartProtection.PREPARATION_TIMEOUT_MS);
    assertEquals(-1, fixture.internalStop());
    assertEquals(0, fixture.lifecycle.generation());
    assertFalse(fixture.lifecycle.isStopping());
    assertFalse(fixture.lifecycle.isStarting());
    assertFalse(fixture.lifecycle.isUserStopped());
  }

  @Test
  public void lostServiceBeforeFinalStartLeavesNoStartingGeneration() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    assertTrue(fixture.owner.canAdmit(fixture.ticket));
    fixture.serviceCurrent.set(false);
    assertEquals(-1, fixture.admit());
    assertEquals(0, fixture.lifecycle.generation());
    assertFalse(fixture.lifecycle.isStarting());
    assertFalse(fixture.lifecycle.isStopping());
  }

  @Test
  public void lostServiceBeforeInternalStopLeavesNoStoppingGeneration() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    assertTrue(fixture.owner.canAdmit(fixture.ticket));
    fixture.serviceCurrent.set(false);
    assertEquals(-1, fixture.internalStop());
    assertEquals(0, fixture.lifecycle.generation());
    assertFalse(fixture.lifecycle.isStopping());
    assertFalse(fixture.lifecycle.isUserStopped());
  }

  @Test
  public void throwingServiceProofIsRejectedBeforeEitherLifecycleTransition() throws Exception {
    WebLifecycle lifecycle = new WebLifecycle();
    AtomicBoolean throwing = new AtomicBoolean();
    WebStartProtection owner = new WebStartProtection(lifecycle, () -> 0L);
    WebStartProtection.Ticket ticket = owner.begin();
    assertTrue(
        owner.promote(
            ticket.id(),
            new Object(),
            () -> {
              if (throwing.get()) throw new IllegalStateException("service-proof-unavailable");
              return true;
            }));
    ticket.awaitPromotion();
    throwing.set(true);
    assertEquals(-1, owner.beginInternalStop(ticket));
    assertEquals(-1, owner.beginStart(ticket, false));
    assertEquals(0, lifecycle.generation());
    assertFalse(lifecycle.isStopping());
    assertFalse(lifecycle.isStarting());
  }

  @Test
  public void foreignOrNullTicketCannotMutateEitherLifecycle() throws Exception {
    Fixture fixture = new Fixture(), other = new Fixture();
    fixture.promote();
    other.promote();
    assertEquals(-1, fixture.owner.beginStart(other.ticket, false));
    assertEquals(-1, fixture.owner.beginStart(null, false));
    assertEquals(-1, fixture.owner.beginInternalStop(other.ticket));
    assertEquals(-1, fixture.owner.beginInternalStop(null));
    assertEquals(0, fixture.lifecycle.generation());
    assertEquals(0, other.lifecycle.generation());
    assertTrue(fixture.owner.canAdmit(fixture.ticket));
    assertTrue(other.owner.canAdmit(other.ticket));
  }

  @Test
  public void expiryWhileWaitingForInternalStopCannotResumeOrStartAgain() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    long stopped = fixture.internalStop();
    assertTrue(stopped > 0);
    fixture.clock.set(WebStartProtection.PREPARATION_TIMEOUT_MS);
    fixture.lifecycle.finishStop(stopped);
    assertFalse(fixture.owner.resumeAfterStop(fixture.ticket));
    assertEquals(-1, fixture.admit());
    assertEquals(stopped, fixture.lifecycle.generation());
    assertFalse(fixture.lifecycle.isStarting());
    assertFalse(fixture.lifecycle.isStopping());
    assertTrue(fixture.lifecycle.isUserStopped());
  }

  @Test
  public void queuedWorkerMustClaimTheExactTicketAndGenerationOnlyOnce() throws Exception {
    Fixture fixture = new Fixture(), other = new Fixture();
    fixture.promote();
    long generation = fixture.admit();
    assertTrue(generation > 0);
    assertFalse(fixture.owner.claimQueuedStart(null, generation));
    assertFalse(fixture.owner.claimQueuedStart(other.ticket, generation));
    assertFalse(fixture.owner.claimQueuedStart(fixture.ticket, generation + 1));
    assertTrue(fixture.owner.claimQueuedStart(fixture.ticket, generation));
    assertFalse(fixture.owner.claimQueuedStart(fixture.ticket, generation));
    assertEquals(generation, fixture.lifecycle.generation());
  }

  @Test
  public void serviceDeathBeforeWorkerClaimInvalidatesItsExactAdmittedGeneration()
      throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    long generation = fixture.admit();
    assertEquals(generation, fixture.owner.serviceDestroyed(fixture.service));
    assertFalse(fixture.owner.claimQueuedStart(fixture.ticket, generation));
    assertEquals(-1, fixture.owner.serviceDestroyed(fixture.service));
    // The owner only identifies the generation; the controller owns the safe Stop and exit proof.
    assertEquals(generation, fixture.lifecycle.generation());
  }

  @Test
  public void serviceDeathAfterWorkerClaimStillIdentifiesOnlyItsCurrentGeneration()
      throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    long generation = fixture.admit();
    assertTrue(fixture.owner.claimQueuedStart(fixture.ticket, generation));
    assertEquals(-1, fixture.owner.serviceDestroyed(new Object()));
    assertEquals(generation, fixture.owner.serviceDestroyed(fixture.service));
    assertEquals(-1, fixture.owner.serviceDestroyed(fixture.service));
    assertEquals(generation, fixture.lifecycle.generation());
  }

  @Test
  public void lostServiceSlotAtDispatchRejectsWithoutReplayingOrChangingLifecycle()
      throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    long generation = fixture.admit();
    fixture.serviceCurrent.set(false);
    assertFalse(fixture.owner.claimQueuedStart(fixture.ticket, generation));
    fixture.serviceCurrent.set(true);
    assertFalse(fixture.owner.claimQueuedStart(fixture.ticket, generation));
    assertEquals(-1, fixture.owner.serviceDestroyed(fixture.service));
    assertEquals(generation, fixture.lifecycle.generation());
  }

  @Test
  public void throwingServiceProofAtDispatchRejectsWithoutStartingAnotherGeneration()
      throws Exception {
    WebLifecycle lifecycle = new WebLifecycle();
    AtomicBoolean throwing = new AtomicBoolean();
    WebStartProtection owner = new WebStartProtection(lifecycle, () -> 0L);
    WebStartProtection.Ticket ticket = owner.begin();
    assertTrue(
        owner.promote(
            ticket.id(),
            new Object(),
            () -> {
              if (throwing.get()) throw new IllegalStateException("service-proof-unavailable");
              return true;
            }));
    long generation = owner.beginStart(ticket, false);
    throwing.set(true);
    assertFalse(owner.claimQueuedStart(ticket, generation));
    assertFalse(owner.claimQueuedStart(ticket, generation));
    assertEquals(generation, lifecycle.generation());
  }

  @Test
  public void explicitStopCancellationInvalidatesQueuedDispatchWithoutMutatingTheGate()
      throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    long generation = fixture.admit();
    fixture.owner.cancelAll();
    assertFalse(fixture.owner.claimQueuedStart(fixture.ticket, generation));
    assertEquals(-1, fixture.owner.serviceDestroyed(fixture.service));
    assertEquals(generation, fixture.lifecycle.generation());
  }

  @Test
  public void obsoleteServiceCannotSuggestStoppingANewerGeneration() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    long generation = fixture.admit();
    assertTrue(fixture.owner.claimQueuedStart(fixture.ticket, generation));
    long stopped = fixture.lifecycle.beginStop();
    fixture.lifecycle.finishStop(stopped);
    long replacement = fixture.lifecycle.beginStart(false, false);
    assertEquals(-1, fixture.owner.serviceDestroyed(fixture.service));
    assertTrue(fixture.lifecycle.isCurrent(replacement));
    assertTrue(fixture.lifecycle.isStarting());
  }

  @Test
  public void lateOldServiceDeathCannotCancelTheNewServicesQueuedTicket() throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    long generation = fixture.admit();
    assertTrue(fixture.owner.claimQueuedStart(fixture.ticket, generation));
    fixture.owner.cancelAll();
    long stopped = fixture.lifecycle.beginStop();
    fixture.lifecycle.finishStop(stopped);
    WebStartProtection.Ticket fresh = fixture.owner.begin();
    Object replacementService = new Object();
    assertTrue(fixture.owner.promote(fresh.id(), replacementService, () -> true));
    long replacement = fixture.owner.beginStart(fresh, false);
    assertEquals(-1, fixture.owner.serviceDestroyed(fixture.service));
    assertTrue(fixture.owner.claimQueuedStart(fresh, replacement));
    assertEquals(replacement, fixture.owner.serviceDestroyed(replacementService));
    assertTrue(fixture.lifecycle.isCurrent(replacement));
  }

  @Test
  public void preparationTimerAndCloseCannotDiscardTheClaimedServicesExactOwnerMapping()
      throws Exception {
    Fixture fixture = new Fixture();
    fixture.promote();
    long generation = fixture.admit();
    assertTrue(fixture.owner.claimQueuedStart(fixture.ticket, generation));
    fixture.clock.set(WebStartProtection.PREPARATION_TIMEOUT_MS + 1);
    fixture.ticket.close();
    fixture.owner.reject(fixture.ticket.id(), "late-old-promotion-timeout");
    assertEquals(generation, fixture.owner.serviceDestroyed(fixture.service));
  }
}
