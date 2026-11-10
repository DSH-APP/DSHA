package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExternalRateLimitTest {
  @Test
  public void firstCallPassesAndRepeatsInsideGapWait() {
    ExternalRateLimit limit = new ExternalRateLimit(250);
    assertEquals(0L, limit.acquire(1000L));
    assertEquals(150L, limit.acquire(1100L));
    assertEquals(1L, limit.acquire(1249L));
    assertEquals(0L, limit.acquire(1250L));
  }

  @Test
  public void clockRollbackDoesNotLockTheCallerOut() {
    ExternalRateLimit limit = new ExternalRateLimit(250);
    assertEquals(0L, limit.acquire(1000L));
    assertEquals(0L, limit.acquire(500L));
    assertEquals(150L, limit.acquire(600L));
  }

  @Test
  public void zeroGapNeverLimits() {
    ExternalRateLimit limit = new ExternalRateLimit(0);
    assertEquals(0L, limit.acquire(1L));
    assertEquals(0L, limit.acquire(1L));
    assertEquals(250L, new ExternalRateLimit(250).minGapMs());
  }
}
