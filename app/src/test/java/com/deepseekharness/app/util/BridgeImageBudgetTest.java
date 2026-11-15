package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;

public class BridgeImageBudgetTest {
  @Test
  public void holdsOnePermitUntilResponseCompletionAndReleaseIsIdempotent() throws Exception {
    BridgeImageBudget.Lease lease = BridgeImageBudget.tryAcquire();
    assertNotNull(lease);
    var pool = Executors.newFixedThreadPool(4);
    try {
      @SuppressWarnings("unchecked")
      Future<Boolean>[] results = new Future[4];
      for (int i = 0; i < results.length; i++)
        results[i] = pool.submit(() -> BridgeImageBudget.tryAcquire() == null);
      for (Future<Boolean> result : results) assertTrue(result.get());
    } finally {
      pool.shutdownNow();
      lease.close();
      lease.close();
    }
    BridgeImageBudget.Lease next = BridgeImageBudget.tryAcquire();
    assertNotNull(next);
    try {
      assertNull(BridgeImageBudget.tryAcquire());
    } finally {
      next.close();
    }
  }
}
