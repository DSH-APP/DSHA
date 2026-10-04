package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class CoalescingPollerTest {
  @Test
  public void blockedOldReadIsDiscardedAndThreeOwnersShareOneProducer() throws Exception {
    AtomicInteger threads = new AtomicInteger(),
        calls = new AtomicInteger(),
        active = new AtomicInteger(),
        peak = new AtomicInteger();
    ScheduledExecutorService worker =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              threads.incrementAndGet();
              return new Thread(task, "isolated-preview-poller");
            });
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        delivered = new CountDownLatch(3);
    AtomicInteger obsolete = new AtomicInteger();
    List<Integer> values = new CopyOnWriteArrayList<>();
    CoalescingPoller<Integer> poller =
        new CoalescingPoller<>(
            worker,
            () -> {
              int inside = active.incrementAndGet();
              peak.accumulateAndGet(inside, Math::max);
              try {
                int value = calls.incrementAndGet();
                if (value == 1) {
                  entered.countDown();
                  release.await();
                }
                return value;
              } finally {
                active.decrementAndGet();
              }
            },
            3,
            1000);
    ArrayList<AutoCloseable> leases = new ArrayList<>();
    try {
      var old = poller.subscribe("first", value -> obsolete.incrementAndGet());
      leases.add(old);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      leases.add(
          poller.subscribe(
              "second",
              value -> {
                values.add(value);
                delivered.countDown();
              }));
      leases.add(
          poller.subscribe(
              "third",
              value -> {
                values.add(value);
                delivered.countDown();
              }));
      try {
        poller.subscribe("fourth", value -> fail());
        fail("owner limit");
      } catch (IllegalStateException expected) {
        assertEquals("POLL_OWNER_LIMIT", expected.getMessage());
      }
      old.close();
      leases.add(
          poller.subscribe(
              "first",
              value -> {
                values.add(value);
                delivered.countDown();
              }));
      release.countDown();
      assertTrue(delivered.await(3, TimeUnit.SECONDS));
      assertEquals(0, obsolete.get());
      assertEquals(List.of(2, 2, 2), values);
      assertEquals(2, calls.get());
      assertEquals(1, threads.get());
      assertEquals(1, peak.get());
    } finally {
      release.countDown();
      for (AutoCloseable lease : leases) lease.close();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  public void closingLastOwnerAndReopeningDoesNotDeliverOldWorkToNewOwner() throws Exception {
    ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        received = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger(),
        oldCallbacks = new AtomicInteger(),
        newest = new AtomicInteger();
    CoalescingPoller<Integer> poller =
        new CoalescingPoller<>(
            worker,
            () -> {
              int value = calls.incrementAndGet();
              if (value == 1) {
                entered.countDown();
                release.await();
              }
              return value;
            },
            1,
            1000);
    var old = poller.subscribe("owner", value -> oldCallbacks.incrementAndGet());
    CoalescingPoller<Integer>.Subscription next = null;
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      old.close();
      next =
          poller.subscribe(
              "owner",
              value -> {
                newest.set(value);
                received.countDown();
              });
      release.countDown();
      assertTrue(received.await(2, TimeUnit.SECONDS));
      assertEquals(0, oldCallbacks.get());
      assertEquals(2, newest.get());
    } finally {
      release.countDown();
      old.close();
      if (next != null) next.close();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  public void oneProducerOrConsumerFailureDoesNotCancelOtherOwners() throws Exception {
    ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch delivered = new CountDownLatch(1);
    CoalescingPoller<Integer> poller =
        new CoalescingPoller<>(
            worker,
            () -> {
              if (calls.incrementAndGet() == 1)
                throw new Exception("isolated unavailable response");
              return 42;
            },
            2,
            10);
    var failing =
        poller.subscribe(
            "failing",
            value -> {
              throw new IllegalStateException("isolated owner");
            });
    var healthy =
        poller.subscribe(
            "healthy",
            value -> {
              if (value == 42) delivered.countDown();
            });
    try {
      assertTrue(delivered.await(2, TimeUnit.SECONDS));
    } finally {
      failing.close();
      healthy.close();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
    }
  }
}
