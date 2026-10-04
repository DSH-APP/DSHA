package com.deepseekharness.app.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuestScriptQueueTest {
  @Test
  public void recursiveLeaseCannotReleaseOuterOwnerAndWaiterContinuesOnItsOwnThread()
      throws Exception {
    var queue = new GuestScriptQueue(4);
    var outer = queue.enter();
    var nested = queue.enter();
    AtomicBoolean entered = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch waiting = new CountDownLatch(1), done = new CountDownLatch(1);
    Thread worker =
        new Thread(
            () -> {
              waiting.countDown();
              try (var ignored = queue.enter()) {
                entered.set(true);
              } catch (Throwable problem) {
                failure.set(problem);
              } finally {
                done.countDown();
              }
            });
    worker.start();
    assertTrue(waiting.await(1, TimeUnit.SECONDS));
    nested.close();
    assertFalse(entered.get());
    outer.close();
    assertTrue(done.await(1, TimeUnit.SECONDS));
    assertTrue(entered.get());
    assertNull(failure.get());
  }

  @Test
  public void cancelledWaiterDoesNotRunOrPreventNextOperation() throws Exception {
    var queue = new GuestScriptQueue(4);
    var owner = queue.enter();
    AtomicBoolean executed = new AtomicBoolean(), cancelled = new AtomicBoolean();
    CountDownLatch waiting = new CountDownLatch(1), done = new CountDownLatch(1);
    Thread worker =
        new Thread(
            () -> {
              waiting.countDown();
              try (var ignored = queue.enter()) {
                executed.set(true);
              } catch (InterruptedException expected) {
                cancelled.set(true);
              } catch (Exception error) {
                throw new AssertionError(error);
              } finally {
                done.countDown();
              }
            });
    worker.start();
    assertTrue(waiting.await(1, TimeUnit.SECONDS));
    worker.interrupt();
    assertTrue(done.await(1, TimeUnit.SECONDS));
    assertFalse(executed.get());
    assertTrue(cancelled.get());
    owner.close();
    try (var next = queue.enter()) {
      assertNotNull(next);
    }
  }

  @Test
  public void leaseCanOnlyBeClosedByItsCaller() throws Exception {
    var queue = new GuestScriptQueue(4);
    var owner = queue.enter();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                owner.close();
              } catch (Throwable problem) {
                failure.set(problem);
              }
            });
    worker.start();
    worker.join(1000);
    assertTrue(failure.get() instanceof IllegalStateException);
    owner.close();
    try (var next = queue.enter()) {
      assertNotNull(next);
    }
  }
}
