package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ReadinessCacheTest {
  private final AtomicInteger checks = new AtomicInteger();
  private final AtomicBoolean ready = new AtomicBoolean(true);
  private final AtomicReference<String> key = new AtomicReference<>("k1");
  private final AtomicLong epoch = new AtomicLong(0);
  private final AtomicLong clock = new AtomicLong(1_000);

  private ReadinessCache cache() {
    return new ReadinessCache(
        () -> {
          checks.incrementAndGet();
          return ready.get();
        },
        key::get,
        epoch::get,
        clock::get,
        10_000);
  }

  @Test
  public void repeatedHotPathReadsDoNotRecompute() {
    ReadinessCache cache = cache();
    // 模拟一次粘贴：数百个码点各调一次。
    for (int i = 0; i < 500; i++) assertTrue(cache.cached());
    assertEquals(1, checks.get());
  }

  @Test
  public void gateRefreshAlwaysRecomputesAndSeedsCache() {
    ReadinessCache cache = cache();
    assertTrue(cache.refresh());
    assertTrue(cache.refresh());
    assertEquals(2, checks.get());
    assertTrue(cache.cached());
    assertEquals(2, checks.get());
  }

  @Test
  public void maintenanceEpochChangeInvalidates() {
    ReadinessCache cache = cache();
    assertTrue(cache.cached());
    epoch.set(2); // 一次完整维护已结束。
    ready.set(false);
    assertFalse(cache.cached());
    assertEquals(2, checks.get());
    assertFalse(cache.cached());
    assertEquals(2, checks.get());
  }

  @Test
  public void activeMaintenanceNeverUsesCache() {
    ReadinessCache cache = cache();
    assertTrue(cache.cached());
    epoch.set(1); // 维护进行中。
    ready.set(false);
    assertFalse(cache.cached());
    assertFalse(cache.cached());
    assertEquals(3, checks.get());
  }

  @Test
  public void unknownEpochNeverCaches() {
    epoch.set(ReadinessCache.EPOCH_UNKNOWN);
    ReadinessCache cache = cache();
    cache.cached();
    cache.cached();
    assertEquals(2, checks.get());
  }

  @Test
  public void fingerprintChangeInvalidates() {
    ReadinessCache cache = cache();
    assertTrue(cache.cached());
    key.set("k2"); // 描述符文件 mtime/size 变化。
    ready.set(false);
    assertFalse(cache.cached());
    assertEquals(2, checks.get());
  }

  @Test
  public void fingerprintFailureFallsBackToFullCheck() {
    ReadinessCache cache =
        new ReadinessCache(
            () -> {
              checks.incrementAndGet();
              return true;
            },
            () -> {
              throw new IllegalStateException("stat failed");
            },
            epoch::get,
            clock::get,
            10_000);
    assertTrue(cache.cached());
    assertTrue(cache.cached());
    assertEquals(2, checks.get());
  }

  @Test
  public void ttlExpiryAndClockRegressionRecompute() {
    ReadinessCache cache = cache();
    cache.cached();
    clock.addAndGet(9_999);
    cache.cached();
    assertEquals(1, checks.get());
    clock.addAndGet(1);
    cache.cached();
    assertEquals(2, checks.get());
    clock.set(0); // 时钟倒退不得延长有效期。
    cache.cached();
    assertEquals(3, checks.get());
  }

  @Test
  public void explicitInvalidateRecomputes() {
    ReadinessCache cache = cache();
    cache.cached();
    cache.invalidate();
    cache.cached();
    assertEquals(2, checks.get());
  }

  @Test
  public void changeDuringFullCheckIsNotCached() {
    AtomicInteger calls = new AtomicInteger();
    ReadinessCache cache =
        new ReadinessCache(
            () -> {
              calls.incrementAndGet();
              epoch.addAndGet(2); // 检查期间完成了一次维护。
              return true;
            },
            key::get,
            epoch::get,
            clock::get,
            10_000);
    cache.cached();
    cache.cached();
    assertEquals(2, calls.get());
  }

  @Test
  public void rejectsNonPositiveTtl() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReadinessCache(() -> true, () -> "", () -> 0, () -> 0, 0));
  }
}
