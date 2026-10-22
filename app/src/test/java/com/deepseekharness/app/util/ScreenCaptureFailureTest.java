package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Deterministic cause ownership tests; no Android capture, input, service or diagnostic storage. */
public class ScreenCaptureFailureTest {
  private Exception failure() {
    Exception result =
        new UnsupportedOperationException("platform detail", new IllegalStateException("origin"));
    result.addSuppressed(new IllegalArgumentException("cleanup"));
    return result;
  }

  @Test
  public void timelyCauseTransfersUnmodifiedAndOnlyOnce() throws Exception {
    AtomicInteger late = new AtomicInteger();
    var delivery =
        new ScreenCaptureDelivery<String>(
            () -> true, ignored -> {}, ignored -> late.incrementAndGet());
    Exception original = failure();
    delivery.fail("SCREEN_OPERATION_UNAVAILABLE", original);
    assertNull(delivery.await(100));
    assertEquals("SCREEN_OPERATION_UNAVAILABLE", delivery.error());
    Throwable handed = delivery.takeFailure();
    assertSame(original, handed);
    assertSame(original.getCause(), handed.getCause());
    assertSame(original.getSuppressed()[0], handed.getSuppressed()[0]);
    assertNull(delivery.takeFailure());
    delivery.close();
    delivery.fail("SCREEN_OPERATION_UNAVAILABLE", original);
    assertEquals(0, late.get());
  }

  @Test
  public void systemStatusNeverInventsThrowable() throws Exception {
    AtomicInteger late = new AtomicInteger();
    var delivery =
        new ScreenCaptureDelivery<String>(
            () -> true, ignored -> {}, ignored -> late.incrementAndGet());
    delivery.fail("SCREENSHOT_SYSTEM_1");
    assertNull(delivery.await(100));
    assertNull(delivery.takeFailure());
    assertEquals("SCREENSHOT_SYSTEM_1", delivery.error());
    delivery.close();
    assertEquals(0, late.get());
  }

  @Test
  public void failureAfterCloseHasOneLateOwnerAndNoWaiterCause() {
    AtomicInteger count = new AtomicInteger();
    AtomicReference<Throwable> seen = new AtomicReference<>();
    var delivery =
        new ScreenCaptureDelivery<String>(
            () -> true,
            ignored -> {},
            error -> {
              count.incrementAndGet();
              seen.set(error);
            });
    Exception original = failure();
    delivery.close();
    delivery.fail("SCREEN_OPERATION_UNAVAILABLE", original);
    delivery.fail("SCREEN_OPERATION_UNAVAILABLE", original);
    delivery.close();
    assertEquals(1, count.get());
    assertSame(original, seen.get());
    assertNull(delivery.takeFailure());
  }

  @Test
  public void failureBetweenTimeoutTakeAndCloseHasOneLateOwner() throws Exception {
    AtomicInteger count = new AtomicInteger();
    AtomicReference<Throwable> seen = new AtomicReference<>();
    var delivery =
        new ScreenCaptureDelivery<String>(
            () -> true,
            ignored -> {},
            error -> {
              count.incrementAndGet();
              seen.set(error);
            });
    assertNull(delivery.await(0));
    assertEquals("SCREENSHOT_TIMEOUT", delivery.error());
    assertNull(delivery.takeFailure());
    Exception original = failure();
    delivery.fail("SCREEN_OPERATION_UNAVAILABLE", original);
    delivery.close();
    assertEquals(1, count.get());
    assertSame(original, seen.get());
  }

  @Test
  public void unclaimedCauseAtCloseIsNotLostOrReturnedTwice() {
    AtomicInteger count = new AtomicInteger();
    AtomicReference<Throwable> seen = new AtomicReference<>();
    var delivery =
        new ScreenCaptureDelivery<String>(
            () -> true,
            ignored -> {},
            error -> {
              count.incrementAndGet();
              seen.set(error);
            });
    Exception original = failure();
    delivery.fail("SCREEN_OPERATION_UNAVAILABLE", original);
    delivery.close();
    delivery.close();
    assertEquals(1, count.get());
    assertSame(original, seen.get());
    assertNull(delivery.takeFailure());
  }

  @Test
  public void brokenLateSinkCannotChangeReleaseOrResult() throws Exception {
    AtomicInteger release = new AtomicInteger();
    AtomicInteger count = new AtomicInteger();
    var delivery =
        new ScreenCaptureDelivery<String>(
            () -> true,
            ignored -> release.incrementAndGet(),
            error -> {
              count.incrementAndGet();
              throw new IllegalStateException("sink failed");
            });
    delivery.close();
    delivery.complete("late-buffer");
    delivery.fail("SCREEN_OPERATION_UNAVAILABLE", failure());
    delivery.close();
    assertNull(delivery.await(1));
    assertEquals(1, count.get());
    assertEquals(1, release.get());
  }

  @Test
  public void originalConstructorAndSuccessfulOwnershipRemainCompatible() throws Exception {
    AtomicInteger release = new AtomicInteger();
    var delivery =
        new ScreenCaptureDelivery<String>(() -> true, ignored -> release.incrementAndGet());
    delivery.complete("buffer");
    assertEquals("buffer", delivery.await(100));
    assertNull(delivery.takeFailure());
    delivery.close();
    assertEquals(0, release.get());
  }
}
