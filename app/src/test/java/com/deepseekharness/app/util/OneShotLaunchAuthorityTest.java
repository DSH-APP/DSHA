package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class OneShotLaunchAuthorityTest {
  private static final String TICKET = "0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String COMMAND =
      "app_process -Djava.class.path=/data/app/com.dsh.client/base.apk /system/bin "
          + "com.deepseekharness.app.vscreen.VirtualScreenCore --launch --port 8800";

  @Test
  public void planAndCommitAreExactGenerationBoundAndOneUse() {
    OneShotLaunchAuthority authority = new OneShotLaunchAuthority();
    assertTrue(authority.issue(TICKET, COMMAND, 4, 100, 30_000));
    assertEquals(TICKET, authority.ticketFor(COMMAND, 4, 101));
    assertEquals("", authority.ticketFor(COMMAND + " --token leaked", 4, 101));
    assertEquals("", authority.ticketFor(COMMAND, 5, 101));
    assertFalse(authority.authorize(TICKET, COMMAND + "x", 4, 102));
    assertFalse(authority.commit(TICKET, 4, 103));
    assertTrue(authority.authorize(TICKET, COMMAND, 4, 104));
    assertFalse(authority.authorize(TICKET, COMMAND, 4, 105));
    assertFalse(authority.commit(TICKET, 5, 106));
    assertTrue(authority.commit(TICKET, 4, 107));
    assertFalse(authority.commit(TICKET, 4, 108));
    assertEquals("", authority.ticketFor(COMMAND, 4, 109));
  }

  @Test
  public void revokeAndExpiryInvalidateOutstandingPlan() {
    OneShotLaunchAuthority revoked = new OneShotLaunchAuthority();
    assertTrue(revoked.issue(TICKET, COMMAND, 9, 100, 30));
    assertTrue(revoked.authorize(TICKET, COMMAND, 9, 101));
    revoked.cancel(9);
    assertFalse(revoked.commit(TICKET, 9, 102));

    OneShotLaunchAuthority expired = new OneShotLaunchAuthority();
    assertTrue(expired.issue(TICKET, COMMAND, 9, 100, 30));
    assertTrue(expired.authorize(TICKET, COMMAND, 9, 129));
    assertFalse(expired.commit(TICKET, 9, 130));
    assertEquals("", expired.ticketFor(COMMAND, 9, 131));
  }

  @Test
  public void invalidLeaseAndConcurrentReservationFailClosed() {
    OneShotLaunchAuthority authority = new OneShotLaunchAuthority();
    assertFalse(authority.issue("short", COMMAND, 1, 0, 10));
    assertFalse(authority.issue(TICKET, COMMAND, 1, 0, 0));
    assertTrue(authority.issue(TICKET, COMMAND, 1, 0, 10));
    assertFalse(authority.issue(TICKET, COMMAND, 2, 0, 10));
    assertFalse(authority.authorize(TICKET, COMMAND, 1, 10));
  }

  @Test
  public void onlyTheExactLiveUncommittedPermitCanBeWithdrawn() {
    OneShotLaunchAuthority authority = new OneShotLaunchAuthority();
    assertTrue(authority.issue(TICKET, COMMAND, 4, 100, 30_000));
    assertTrue(authority.authorize(TICKET, COMMAND, 4, 101));
    assertFalse(authority.withdrawUncommitted("f".repeat(48), COMMAND, 4, 102));
    assertFalse(authority.withdrawUncommitted(TICKET, COMMAND + "x", 4, 102));
    assertFalse(authority.withdrawUncommitted(TICKET, COMMAND, 5, 102));
    assertTrue(authority.withdrawUncommitted(TICKET, COMMAND, 4, 102));
    assertFalse(authority.commit(TICKET, 4, 103));
    assertFalse(authority.withdrawUncommitted(TICKET, COMMAND, 4, 103));
  }

  @Test
  public void consumedExpiredCanceledAndOldPermitsNeverProveNotSent() {
    for (String disposition : new String[] {"consumed", "expired", "canceled", "old"}) {
      OneShotLaunchAuthority authority = new OneShotLaunchAuthority();
      assertTrue(authority.issue(TICKET, COMMAND, 4, 100, 30));
      assertTrue(authority.authorize(TICKET, COMMAND, 4, 101));
      if (disposition.equals("consumed")) assertTrue(authority.commit(TICKET, 4, 102));
      else if (disposition.equals("expired"))
        assertEquals("", authority.ticketFor(COMMAND, 4, 130));
      else {
        authority.cancel(4);
        if (disposition.equals("old"))
          assertTrue(authority.issue("f".repeat(48), COMMAND, 5, 103, 30));
      }
      assertFalse(disposition, authority.withdrawUncommitted(TICKET, COMMAND, 4, 104));
    }
  }

  @Test
  public void commitAndWithdrawalHaveExactlyOneAtomicWinner() throws Exception {
    for (int attempt = 0; attempt < 64; attempt++) {
      OneShotLaunchAuthority authority = new OneShotLaunchAuthority();
      assertTrue(authority.issue(TICKET, COMMAND, 4, 100, 30_000));
      assertTrue(authority.authorize(TICKET, COMMAND, 4, 101));
      var start = new java.util.concurrent.CountDownLatch(1);
      var committed = new java.util.concurrent.atomic.AtomicBoolean();
      var withdrawn = new java.util.concurrent.atomic.AtomicBoolean();
      Thread commit =
          new Thread(
              () -> {
                try {
                  start.await();
                  committed.set(authority.commit(TICKET, 4, 102));
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              });
      Thread withdraw =
          new Thread(
              () -> {
                try {
                  start.await();
                  withdrawn.set(authority.withdrawUncommitted(TICKET, COMMAND, 4, 102));
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              });
      commit.start();
      withdraw.start();
      start.countDown();
      commit.join(5000);
      withdraw.join(5000);
      assertFalse(commit.isAlive());
      assertFalse(withdraw.isAlive());
      assertTrue(committed.get() ^ withdrawn.get());
      assertFalse(authority.commit(TICKET, 4, 103));
      assertFalse(authority.withdrawUncommitted(TICKET, COMMAND, 4, 103));
    }
  }
}
