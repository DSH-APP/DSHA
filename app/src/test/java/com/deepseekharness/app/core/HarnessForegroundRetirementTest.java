package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.SharedServiceLeases;
import com.deepseekharness.app.util.WebLifecycle;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class HarnessForegroundRetirementTest {
  private static WebStopCoordinator.Result stopped() {
    return new WebStopCoordinator.Result(WebStopCoordinator.Status.STOPPED, "WEB_STOPPED", "");
  }

  private static long runningThenStop(WebLifecycle lifecycle) {
    long running = lifecycle.beginStart(false, false);
    assertTrue(lifecycle.finishStart(running));
    return lifecycle.beginStop();
  }

  @Test
  public void confirmedStopRetiresOnlyAfterFinishAndWhileHoldingItsGenerationFence() {
    var lifecycle = new WebLifecycle();
    long generation = runningThenStop(lifecycle);
    var retired = new AtomicInteger();
    Runnable queued =
        WebLifecycleController.foregroundRetirement(
            lifecycle,
            generation,
            stopped(),
            () -> {
              assertTrue(Thread.holdsLock(lifecycle));
              retired.incrementAndGet();
            });
    queued.run();
    assertEquals(0, retired.get());
    lifecycle.finishStop(generation);
    queued.run();
    assertEquals(1, retired.get());
  }

  @Test
  public void unavailableCancelledAndFailedExitProofsRetainTheService() {
    var lifecycle = new WebLifecycle();
    long generation = runningThenStop(lifecycle);
    lifecycle.finishStop(generation);
    var retired = new AtomicInteger();
    WebLifecycleController.foregroundRetirement(
            lifecycle, generation, null, retired::incrementAndGet)
        .run();
    for (var status :
        new WebStopCoordinator.Status[] {
          WebStopCoordinator.Status.UNCONFIRMED,
          WebStopCoordinator.Status.FAILED,
          WebStopCoordinator.Status.CANCELLED
        })
      WebLifecycleController.foregroundRetirement(
              lifecycle,
              generation,
              new WebStopCoordinator.Result(status, "EXIT_NOT_CONFIRMED", ""),
              retired::incrementAndGet)
          .run();
    assertEquals(0, retired.get());
  }

  @Test
  public void stopExceptionsReplaceOldSuccessAndKeepTheServiceAfterFinish() {
    for (Throwable failure :
        new Throwable[] {
          new IllegalStateException("coordinator failed"),
          new IllegalStateException("bridge release failed"),
          new IllegalStateException("backup scheduling failed"),
          new IllegalStateException(
              new com.deepseekharness.app.util.WebStopException(
                  com.deepseekharness.app.util.WebStopDiagnostic.Code.LEGACY_DETAIL, "")),
          new AssertionError("stop aborted")
        }) {
      var state = new HarnessSessionState();
      long generation = runningThenStop(state.lifecycle);
      var oldProof = stopped();
      state.lastStopResult = oldProof;
      state.lastStopError = "";
      var failed = WebLifecycleController.recordStopFailure(state, failure);
      assertSame(failed, state.lastStopResult);
      assertNotSame(oldProof, state.lastStopResult);
      assertEquals(WebStopCoordinator.Status.FAILED, state.lastStopResult.status());
      assertEquals("WEB_STOP_FAILED", state.lastStopResult.code());
      assertFalse(state.lastStopError.isBlank());
      assertEquals(state.lastStopResult.detail(), state.lastStopError);
      state.lifecycle.finishStop(generation);
      var retired = new AtomicInteger();
      WebLifecycleController.foregroundRetirement(
              state.lifecycle, generation, state.lastStopResult, retired::incrementAndGet)
          .run();
      assertEquals(0, retired.get());
      assertTrue(
          WebLifecycleController.forceStopAllowed(
              state.lifecycle, state.lastStopResult, generation));
      state.io.shutdownNow();
    }
  }

  @Test
  public void startWhileRetirementIsQueuedProtectsTheNewStartingAndReadyRun() {
    var lifecycle = new WebLifecycle();
    long generation = runningThenStop(lifecycle);
    lifecycle.finishStop(generation);
    var retired = new AtomicInteger();
    Runnable queued =
        WebLifecycleController.foregroundRetirement(
            lifecycle, generation, stopped(), retired::incrementAndGet);
    long next = lifecycle.beginStart(false, false);
    assertTrue(next > generation);
    queued.run();
    assertTrue(lifecycle.finishStart(next));
    queued.run();
    assertEquals(0, retired.get());
  }

  @Test
  public void laterStopCannotBorrowAnEarlierGenerationsExitProof() {
    var lifecycle = new WebLifecycle();
    long first = runningThenStop(lifecycle);
    lifecycle.finishStop(first);
    var retired = new AtomicInteger();
    Runnable old =
        WebLifecycleController.foregroundRetirement(
            lifecycle, first, stopped(), retired::incrementAndGet);
    long next = lifecycle.beginStart(false, false);
    lifecycle.finishStart(next);
    long second = lifecycle.beginStop();
    lifecycle.finishStop(second);
    old.run();
    assertEquals(0, retired.get());
    WebLifecycleController.foregroundRetirement(
            lifecycle, second, stopped(), retired::incrementAndGet)
        .run();
    assertEquals(1, retired.get());
  }

  @Test
  public void retiringHarnessDemandKeepsTheIndependentDeviceBridgeLease() {
    var lifecycle = new WebLifecycle();
    long generation = runningThenStop(lifecycle);
    lifecycle.finishStop(generation);
    var bridgeStops = new AtomicInteger();
    var leases =
        new SharedServiceLeases<Object>(bridge -> {}, bridge -> bridgeStops.incrementAndGet());
    var harness = leases.acquire(Object::new);
    var device = leases.acquire(Object::new);
    WebLifecycleController.foregroundRetirement(lifecycle, generation, stopped(), harness::close)
        .run();
    assertEquals(0, bridgeStops.get());
    device.ensureStarted();
    device.close();
    assertEquals(1, bridgeStops.get());
  }
}
