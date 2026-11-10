package com.deepseekharness.app.screen;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public final class AccessibilityReadDispatcherTest {
  @Test
  public void eventBurstKeepsOnlyOneLatestPendingReadAndNeverRunsOnCaller() throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    AccessibilityReadDispatcher reads = new AccessibilityReadDispatcher(worker);
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        done = new CountDownLatch(1);
    AtomicInteger ran = new AtomicInteger();
    Thread caller = Thread.currentThread();
    try {
      reads.submit(
          () -> true,
          () -> {
            assertNotSame(caller, Thread.currentThread());
            entered.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
              throw new AssertionError(error);
            }
            ran.incrementAndGet();
          });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      for (int i = 0; i < 10000; i++) reads.submit(() -> true, ran::incrementAndGet);
      reads.submit(
          () -> true,
          () -> {
            ran.incrementAndGet();
            done.countDown();
          });
      release.countDown();
      assertTrue(done.await(2, TimeUnit.SECONDS));
      assertEquals(2, ran.get());
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  public void canceledOldWatchNeverRunsAfterANewerWatchIsAdmitted() {
    List<Runnable> queue = new ArrayList<>();
    AccessibilityReadDispatcher reads = new AccessibilityReadDispatcher(queue::add);
    AtomicInteger watch = new AtomicInteger(1),
        old = new AtomicInteger(),
        fresh = new AtomicInteger();
    reads.submit(() -> watch.get() == 1, old::incrementAndGet);
    watch.set(2);
    reads.submit(() -> watch.get() == 2, fresh::incrementAndGet);
    assertEquals(1, queue.size());
    queue.remove(0).run();
    assertEquals(0, old.get());
    assertEquals(1, fresh.get());
  }

  @Test
  public void staleQueuedWatchAndExecutorRejectionDoNotReadAnyNodes() {
    List<Runnable> queue = new ArrayList<>();
    AccessibilityReadDispatcher reads = new AccessibilityReadDispatcher(queue::add);
    AtomicBoolean current = new AtomicBoolean(true);
    AtomicInteger calls = new AtomicInteger();
    reads.submit(current::get, calls::incrementAndGet);
    current.set(false);
    queue.remove(0).run();
    assertEquals(0, calls.get());
    AccessibilityReadDispatcher rejected =
        new AccessibilityReadDispatcher(
            task -> {
              throw new RejectedExecutionException();
            });
    rejected.submit(() -> true, calls::incrementAndGet);
    assertEquals(0, calls.get());
  }
}
