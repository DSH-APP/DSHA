package com.deepseekharness.app;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class LanLoginThrottleTest {
  @Test
  public void attemptsRecoverAfterWindowOrSuccessfulAuthentication() {
    AtomicLong now = new AtomicLong(1);
    LanLoginThrottle limiter = new LanLoginThrottle(now::get);
    for (int i = 0; i < 6; i++) assertTrue(limiter.acquire("peer"));
    assertFalse(limiter.acquire("peer"));
    assertTrue(limiter.acquire("other"));
    limiter.success("other");
    for (int i = 0; i < 6; i++) assertTrue(limiter.acquire("other"));
    assertFalse(limiter.acquire("other"));
    now.addAndGet(60_000_000_000L);
    assertTrue(limiter.acquire("peer"));
  }
}
