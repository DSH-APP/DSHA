package com.deepseekharness.app.backup;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DataProtectionServiceStartTicketTest {
  @Test
  public void protectedWorkWaitsForActualPromotion() throws Exception {
    var ticket = new DataProtectionService.StartTicket(1);
    var waiting = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var ran = new AtomicBoolean();
    var failure = new AtomicReference<Throwable>();
    Thread worker =
        new Thread(
            () -> {
              waiting.countDown();
              try {
                ticket.await();
                ran.set(true);
              } catch (Throwable error) {
                failure.set(error);
              } finally {
                completed.countDown();
              }
            });
    worker.start();
    assertTrue(waiting.await(1, TimeUnit.SECONDS));
    assertFalse(completed.await(50, TimeUnit.MILLISECONDS));
    assertFalse(ran.get());
    ticket.complete(null);
    assertTrue(completed.await(1, TimeUnit.SECONDS));
    assertNull(failure.get());
    assertTrue(ran.get());
  }

  @Test
  public void deniedPromotionNeverRunsProtectedWork() throws Exception {
    var ticket = new DataProtectionService.StartTicket(2);
    var failure = new IOException("FOREGROUND_SERVICE_UNAVAILABLE");
    ticket.complete(failure);
    assertSame(failure, assertThrows(IOException.class, ticket::await));
  }
}
