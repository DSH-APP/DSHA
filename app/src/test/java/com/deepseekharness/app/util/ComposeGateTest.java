package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class ComposeGateTest {
  private final AtomicLong now = new AtomicLong(1_000);
  private final ComposeGate gate = new ComposeGate(now::get);

  private Thread waiter(AtomicReference<ComposeGate.Outcome> result, CountDownLatch started) {
    Thread thread =
        new Thread(
            () -> {
              try {
                started.countDown();
                result.set(gate.await(60_000, () -> true));
              } catch (InterruptedException interrupted) {
                result.set(null);
              }
            });
    thread.start();
    return thread;
  }

  @Test
  public void notComposingPassesImmediately() throws Exception {
    assertEquals(ComposeGate.Outcome.CLEAR, gate.await(10, () -> true));
  }

  @Test
  public void sendingWakesTheWaiterWithMessageArrived() throws Exception {
    gate.begin("overlay");
    var result = new AtomicReference<ComposeGate.Outcome>();
    var started = new CountDownLatch(1);
    Thread thread = waiter(result, started);
    assertTrue(started.await(2, TimeUnit.SECONDS));
    Thread.sleep(50);
    gate.sent();
    thread.join(2000);
    assertEquals(ComposeGate.Outcome.MESSAGE_ARRIVED, result.get());
    assertFalse(gate.composing());
  }

  @Test
  public void leavingWithoutSendingResumesTheAction() throws Exception {
    gate.begin("editor");
    var result = new AtomicReference<ComposeGate.Outcome>();
    var started = new CountDownLatch(1);
    Thread thread = waiter(result, started);
    assertTrue(started.await(2, TimeUnit.SECONDS));
    Thread.sleep(50);
    assertFalse("another surface cannot release it", gate.release("voice"));
    assertTrue(gate.release("editor"));
    thread.join(2000);
    assertEquals(ComposeGate.Outcome.CLEAR, result.get());
  }

  @Test
  public void limitReturnsStillComposingAndTaskChangeInterrupts() throws Exception {
    gate.begin("overlay");
    now.addAndGet(10);
    var outcome = gate.await(0, () -> true);
    assertEquals(ComposeGate.Outcome.STILL_COMPOSING, outcome);
    assertEquals(ComposeGate.Outcome.INTERRUPTED, gate.await(60_000, () -> false));
    assertTrue(gate.composing());
  }

  @Test
  public void anOldSendDoesNotLeakIntoALaterComposition() throws Exception {
    gate.begin("overlay");
    gate.sent();
    gate.begin("overlay");
    gate.release("overlay");
    assertEquals(ComposeGate.Outcome.CLEAR, gate.await(10, () -> true));
    gate.begin("voice");
    gate.begin("editor");
    assertEquals("editor", gate.surface());
    gate.reset();
    assertFalse(gate.composing());
    assertEquals(ComposeGate.Outcome.CLEAR, gate.await(10, () -> true));
  }

  @Test(expected = IllegalArgumentException.class)
  public void missingClockIsRejected() {
    new ComposeGate(null);
  }
}
