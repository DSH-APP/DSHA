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
}
