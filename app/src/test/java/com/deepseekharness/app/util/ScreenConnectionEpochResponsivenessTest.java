package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.concurrent.*;
import org.junit.Test;

/** Deliberately blocked claim callbacks must not own the service lifecycle lock. */
public final class ScreenConnectionEpochResponsivenessTest {
  @Test
  public void lifecycleCanRevokeWhileClaimCallbackHasNotReturned() throws Exception {
    ScreenConnectionEpoch<Object> connection = new ScreenConnectionEpoch<>();
    Object service = new Object();
    connection.connect(service);
    var lease = connection.snapshot();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    var threads = Executors.newFixedThreadPool(2);
    try {
      var claim =
          threads.submit(
              () ->
                  connection.claim(
                      lease,
                      () -> {
                        entered.countDown();
                        try {
                          assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                          throw new AssertionError(interrupted);
                        }
                        return "old-permit";
                      }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var disconnect = threads.submit(() -> connection.disconnect(service));
      assertTrue(disconnect.get(1, TimeUnit.SECONDS));
      assertNull(connection.snapshot());
      release.countDown();
      assertNull(claim.get(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      threads.shutdownNow();
    }
  }

  @Test
  public void callbackCannotReturnAPermitFromItsOwnRevokedEpoch() {
    ScreenConnectionEpoch<Object> connection = new ScreenConnectionEpoch<>();
    connection.connect(new Object());
    var lease = connection.snapshot();
    assertNull(
        connection.claim(
            lease,
            () -> {
              connection.revoke();
              return "stale-permit";
            }));
    assertNotNull(connection.snapshot());
    assertFalse(connection.current(lease));
  }

  @Test
  public void callbackCannotReplaceServiceAndReturnAnOldPermit() {
    ScreenConnectionEpoch<Object> connection = new ScreenConnectionEpoch<>();
    Object old = new Object(), replacement = new Object();
    connection.connect(old);
    var lease = connection.snapshot();
    assertNull(
        connection.claim(
            lease,
            () -> {
              connection.connect(replacement);
              return "old-permit";
            }));
    assertSame(replacement, connection.snapshot().service);
    assertFalse(connection.disconnect(old));
  }
}
