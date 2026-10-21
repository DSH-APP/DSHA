package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** 软件所有权与竞态测试，不代表Android窗口/截图/手势实测。 */
public class ScreenOwnershipTest {
  private static final ScreenTarget TARGET = new ScreenTarget(0, 7, "example.app");

  @Test
  public void oldServiceDestructionDoesNotDisconnectReplacement() {
    ScreenConnectionEpoch<Object> connection = new ScreenConnectionEpoch<>();
    Object first = new Object(), second = new Object();
    connection.connect(first);
    var old = connection.snapshot();
    connection.connect(second);
    var current = connection.snapshot();
    assertFalse(connection.disconnect(first));
    assertTrue(connection.current(current));
    assertFalse(connection.current(old));
  }

  @Test
  public void revokeFencesClaimsWithoutDisconnectingService() {
    ScreenConnectionEpoch<Object> connection = new ScreenConnectionEpoch<>();
    connection.connect(new Object());
    var old = connection.snapshot();
    connection.revoke();
    AtomicInteger claims = new AtomicInteger();
    assertNull(connection.claim(old, claims::incrementAndGet));
    assertEquals(0, claims.get());
    assertNotNull(connection.snapshot());
    assertEquals(
        Integer.valueOf(1), connection.claim(connection.snapshot(), claims::incrementAndGet));
  }

  @Test
  public void pixelsAreOneUseAndGeometryAndContentAreIndependentProof() {
    AtomicLong now = new AtomicLong(100);
    ScreenObservationLedger ledger = new ScreenObservationLedger(now::get);
    var issued = ledger.publish(TARGET, 3, 100, 200, 0, "pixels");
    var consumed = ledger.consume(issued.id, TARGET, 3);
    assertNotNull(consumed);
    assertTrue(consumed.matches(100, 200, 0, "pixels"));
    assertFalse(consumed.matches(200, 100, 1, "pixels"));
    assertFalse(consumed.matches(100, 200, 0, "pip-overlaid"));
    assertNull(ledger.consume(issued.id, TARGET, 3));
  }

  @Test
  public void staleWindowDisplayAndConnectionNeverConsumeAnotherTarget() {
    AtomicLong now = new AtomicLong(100);
    ScreenObservationLedger ledger = new ScreenObservationLedger(now::get);
    for (ScreenTarget other :
        new ScreenTarget[] {
          new ScreenTarget(1, 7, "example.app"),
          new ScreenTarget(0, 8, "example.app"),
          new ScreenTarget(0, 7, "another.app")
        }) {
      var issued = ledger.publish(TARGET, 3, 100, 200, 0, "pixels");
      assertNull(ledger.consume(issued.id, other, 3));
      assertNull(ledger.consume(issued.id, TARGET, 3));
    }
    var issued = ledger.publish(TARGET, 3, 100, 200, 0, "pixels");
    assertNull(ledger.consume(issued.id, TARGET, 4));
  }

  @Test
  public void onlyLatestPublishedObservationAndThirtySecondWindowCanDispatch() {
    AtomicLong now = new AtomicLong(100);
    ScreenObservationLedger ledger = new ScreenObservationLedger(now::get);
    var first = ledger.publish(TARGET, 3, 100, 200, 0, "one");
    var second = ledger.publish(TARGET, 3, 100, 200, 0, "two");
    assertNull(ledger.consume(first.id, TARGET, 3));
    now.addAndGet(30_001);
    assertNull(ledger.consume(second.id, TARGET, 3));
  }

  @Test
  public void observationsAndNodesCannotCrossRuntimeOrAuthorizationOwner() {
    AtomicLong now = new AtomicLong(100);
    ScreenObservationLedger ledger = new ScreenObservationLedger(now::get);
    var seen = ledger.publish(TARGET, 3, "run-one", 100, 200, 0, "pixels");
    assertNull(ledger.consume(seen.id, TARGET, 3, "run-two"));
    AtomicInteger released = new AtomicInteger();
    ScreenNodeLeaseStore<String> nodes =
        new ScreenNodeLeaseStore<>(now::get, value -> released.incrementAndGet());
    String tree = nodes.begin(TARGET, 3, "grant-one"), node = nodes.add("node");
    assertNull(nodes.consume(tree, node, TARGET, 3, "grant-two"));
    assertEquals(1, released.get());
  }

  @Test
  public void treeNodesAreIndependentOfPixelRefreshAndAlwaysReleased() {
    AtomicLong now = new AtomicLong(100);
    AtomicInteger released = new AtomicInteger();
    ScreenNodeLeaseStore<String> nodes =
        new ScreenNodeLeaseStore<>(now::get, value -> released.incrementAndGet());
    String tree = nodes.begin(TARGET, 3);
    String node = nodes.add("retained-handle");
    ScreenObservationLedger pixels = new ScreenObservationLedger(now::get);
    pixels.publish(TARGET, 3, 100, 200, 0, "first");
    pixels.publish(TARGET, 3, 100, 200, 0, "new-animation-frame");
    assertEquals("retained-handle", nodes.consume(tree, node, TARGET, 3));
    assertNull(nodes.consume(tree, node, TARGET, 3));
    nodes.add("remaining");
    nodes.clear();
    assertEquals(1, released.get()); // 已消费句柄由执行者释放，其余由store释放。
  }

  @Test
  public void staleTreeClosesAllUnclaimedHandles() {
    AtomicLong now = new AtomicLong(100);
    AtomicInteger released = new AtomicInteger();
    ScreenNodeLeaseStore<String> nodes =
        new ScreenNodeLeaseStore<>(now::get, value -> released.incrementAndGet());
    String tree = nodes.begin(TARGET, 3), node = nodes.add("one");
    nodes.add("two");
    now.addAndGet(30_001);
    assertNull(nodes.consume(tree, node, TARGET, 3));
    assertEquals(2, released.get());
    nodes.clear();
    assertEquals(2, released.get());
  }

  @Test
  public void lateScreenshotAfterTimeoutIsClosedExactlyOnce() throws Exception {
    AtomicInteger released = new AtomicInteger();
    ScreenCaptureDelivery<String> delivery =
        new ScreenCaptureDelivery<>(() -> true, value -> released.incrementAndGet());
    assertNull(delivery.await(1));
    delivery.close();
    delivery.complete("late-buffer");
    delivery.close();
    assertEquals(1, released.get());
  }

  @Test
  public void revokedScreenshotDoesNotTransferOwnedPixels() throws Exception {
    AtomicBoolean valid = new AtomicBoolean(true);
    AtomicInteger released = new AtomicInteger();
    ScreenCaptureDelivery<String> delivery =
        new ScreenCaptureDelivery<>(valid::get, value -> released.incrementAndGet());
    delivery.complete("buffer");
    valid.set(false);
    assertNull(delivery.await(100));
    delivery.close();
    assertEquals(1, released.get());
  }

  @Test
  public void successfulScreenshotTransfersOwnershipOnce() throws Exception {
    AtomicInteger released = new AtomicInteger();
    ScreenCaptureDelivery<String> delivery =
        new ScreenCaptureDelivery<>(() -> true, value -> released.incrementAndGet());
    delivery.complete("buffer");
    assertEquals("buffer", delivery.await(100));
    delivery.close();
    assertEquals(0, released.get());
  }

  @Test
  public void queuedRequestCannotBeginAfterRevocation() throws Exception {
    ScreenSerialExecutor serial = new ScreenSerialExecutor();
    AtomicBoolean current = new AtomicBoolean(true);
    CountDownLatch started = new CountDownLatch(1),
        release = new CountDownLatch(1),
        queued = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    AtomicInteger writes = new AtomicInteger();
    try {
      var first =
          pool.submit(
              () ->
                  serial.execute(
                      () -> true,
                      () -> 100,
                      10_000,
                      () -> {
                        started.countDown();
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return 1;
                      }));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var second =
          pool.submit(
              () -> {
                try {
                  return serial.execute(
                      () -> {
                        queued.countDown();
                        return current.get();
                      },
                      () -> 100,
                      10_000,
                      writes::incrementAndGet);
                } catch (ScreenSerialExecutor.Refused refused) {
                  return -1;
                }
              });
      assertTrue(queued.await(2, TimeUnit.SECONDS));
      current.set(false);
      release.countDown();
      assertEquals(Integer.valueOf(1), first.get(2, TimeUnit.SECONDS));
      assertEquals(Integer.valueOf(-1), second.get(2, TimeUnit.SECONDS));
      assertEquals(0, writes.get());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  public void queueCapacityRejectsBeforePlatformWork() throws Exception {
    ScreenSerialExecutor serial = new ScreenSerialExecutor();
    CountDownLatch entered = new CountDownLatch(16), release = new CountDownLatch(1);
    AtomicInteger writes = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(16);
    var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
    try {
      for (int i = 0; i < 16; i++)
        futures.add(
            pool.submit(
                () ->
                    serial.execute(
                        () -> {
                          entered.countDown();
                          return true;
                        },
                        () -> 100,
                        10_000,
                        () -> {
                          assertTrue(release.await(2, TimeUnit.SECONDS));
                          return writes.incrementAndGet();
                        })));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      try {
        serial.execute(() -> true, () -> 100, 10_000, writes::incrementAndGet);
        fail("queue overflow ran");
      } catch (ScreenSerialExecutor.Refused refused) {
        assertEquals("SCREEN_QUEUE_FULL", refused.code);
      }
      release.countDown();
      for (var future : futures) future.get(2, TimeUnit.SECONDS);
      assertEquals(16, writes.get());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  public void expiredRequestDoesNotRunAndInterruptionPreservesFlag() throws Exception {
    ScreenSerialExecutor serial = new ScreenSerialExecutor();
    AtomicInteger writes = new AtomicInteger();
    try {
      serial.execute(() -> true, () -> 100, 100, writes::incrementAndGet);
      fail("expired request ran");
    } catch (ScreenSerialExecutor.Refused expected) {
      assertEquals(0, writes.get());
    }
    Thread.currentThread().interrupt();
    try {
      serial.execute(() -> true, () -> 100, 1000, writes::incrementAndGet);
      fail("interrupted request ran");
    } catch (ScreenSerialExecutor.Refused expected) {
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(0, writes.get());
    } finally {
      Thread.interrupted();
    }
  }
}
