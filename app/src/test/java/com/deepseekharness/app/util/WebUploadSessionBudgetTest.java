package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebUploadSessionBudgetTest {
  @Test
  public void committedUploadsHavePerPageFileAndByteCaps() {
    WebUploadSessionBudget budget = new WebUploadSessionBudget();
    for (int batch = 0; batch < 2; batch++) {
      var reservation = budget.beginBatch(20);
      budget.addBytes(reservation, 256L * 1024L * 1024L);
      budget.finishCopy(reservation);
      assertTrue(budget.commit(reservation));
    }
    assertEquals(40, budget.committedFiles());
    assertEquals(512L * 1024L * 1024L, budget.committedBytes());
    assertThrows(WebUploadSessionBudget.LimitExceededException.class, () -> budget.beginBatch(1));
  }

  @Test
  public void failedOrStaleBatchReleasesReservationsWithoutWeakeningCaps() {
    WebUploadSessionBudget budget = new WebUploadSessionBudget();
    var abandoned = budget.beginBatch(20);
    budget.addBytes(abandoned, 300L * 1024L * 1024L);
    budget.rollback(abandoned);
    assertEquals(0, budget.reservedFiles());
    assertEquals(0, budget.reservedBytes());
    var accepted = budget.beginBatch(20);
    budget.addBytes(accepted, 256L * 1024L * 1024L);
    budget.finishCopy(accepted);
    assertTrue(budget.commit(accepted));
  }

  @Test
  public void closeRejectsLateCommitAndSignalsInFlightCleanupBoundary() {
    WebUploadSessionBudget budget = new WebUploadSessionBudget();
    var active = budget.beginBatch(1);
    budget.addBytes(active, 17);
    budget.close();
    assertFalse(budget.canDeleteOwnedFiles());
    assertThrows(
        WebUploadSessionBudget.SessionClosedException.class, () -> budget.addBytes(active, 1));
    budget.rollback(active);
    assertTrue(budget.canDeleteOwnedFiles());
    assertFalse(budget.commit(active));
  }

  @Test
  public void concurrentBatchesCannotReserveMoreThanSessionFileLimit() throws Exception {
    WebUploadSessionBudget budget = new WebUploadSessionBudget();
    java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(3);
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger accepted =
        new java.util.concurrent.atomic.AtomicInteger();
    Thread[] workers = new Thread[3];
    for (int i = 0; i < workers.length; i++)
      workers[i] =
          new Thread(
              () -> {
                ready.countDown();
                try {
                  start.await();
                  var reservation = budget.beginBatch(20);
                  accepted.incrementAndGet();
                } catch (WebUploadSessionBudget.LimitExceededException expected) {
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              });
    for (Thread worker : workers) worker.start();
    ready.await();
    start.countDown();
    for (Thread worker : workers) worker.join();
    assertEquals(2, accepted.get());
    assertEquals(40, budget.reservedFiles());
  }
}
