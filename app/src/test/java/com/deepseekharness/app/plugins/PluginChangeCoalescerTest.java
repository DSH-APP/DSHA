package com.deepseekharness.app.plugins;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PluginChangeCoalescerTest {
  @Test
  public void quietBurstUsesLastEventButKeepsFirstDeadline() {
    PluginChangeCoalescer value = new PluginChangeCoalescer(100, 1000, 250);
    value.markDirty(10);
    value.markDirty(70);
    assertEquals(100, value.nextDelayMillis(70));
    assertEquals(10, value.firstDirtyAt());
    assertEquals(1010, value.deadlineAt());
    assertFalse(value.beginScan(169));
    assertTrue(value.beginScan(170));
    assertEquals(-1, value.nextDelayMillis(170));
  }

  @Test
  public void unendingStormCannotMoveFirstEventBoundary() {
    PluginChangeCoalescer value = new PluginChangeCoalescer(100, 1000, 250);
    for (int now = 0; now <= 1000; now += 10) {
      value.markDirty(now);
      assertEquals(1000, value.deadlineAt());
      assertTrue(now + value.nextDelayMillis(now) <= 1000);
    }
    assertTrue(value.beginScan(1000));
  }

  @Test
  public void immediateRequestsStillRespectMinimumStartInterval() {
    PluginChangeCoalescer value = new PluginChangeCoalescer(100, 1000, 250);
    value.requestNow(0);
    assertTrue(value.beginScan(0));
    value.scanFinished(20);
    value.requestNow(30);
    assertEquals(220, value.nextDelayMillis(30));
    assertFalse(value.beginScan(249));
    assertTrue(value.beginScan(250));
  }

  @Test
  public void dirtyDuringScanSurvivesCompletionAndGetsItsOwnDeadline() {
    PluginChangeCoalescer value = new PluginChangeCoalescer(100, 1000, 250);
    value.requestNow(0);
    assertTrue(value.beginScan(0));
    value.markDirty(10);
    value.markDirty(90);
    assertEquals(1010, value.deadlineAt());
    assertEquals(-1, value.nextDelayMillis(120));
    value.scanFinished(200);
    assertTrue(value.hasPending());
    assertEquals(50, value.nextDelayMillis(200));
    assertTrue(value.beginScan(250));
    value.scanFinished(260);
    assertFalse(value.hasPending());
    assertEquals(-1, value.nextDelayMillis(5000));
  }

  @Test
  public void longRunningScanNeverPretendsToFinishOrStartsConcurrentWork() {
    PluginChangeCoalescer value = new PluginChangeCoalescer(100, 1000, 250);
    value.requestNow(0);
    assertTrue(value.beginScan(0));
    value.markDirty(20);
    assertFalse(value.beginScan(2000));
    assertTrue(value.isScanning());
    assertTrue(value.hasPending());
    value.scanFinished(2000);
    assertTrue(value.beginScan(2000));
  }

  @Test
  public void laterOrdinaryEventCannotUndoCompensationRequest() {
    PluginChangeCoalescer value = new PluginChangeCoalescer(100, 1000, 250);
    value.requestNow(10);
    value.markDirty(20);
    assertEquals(0, value.nextDelayMillis(20));
    assertTrue(value.beginScan(20));
  }

  @Test
  public void finishingFailedOrCancelledScanDoesNotEraseNextBatch() {
    PluginChangeCoalescer value = new PluginChangeCoalescer(0, 10, 0);
    value.markDirty(0);
    assertTrue(value.beginScan(0));
    value.requestNow(1);
    value.scanFinished(2);
    assertTrue(value.beginScan(2));
    value.scanFinished(3);
    assertFalse(value.isScanning());
    assertEquals(-1, value.deadlineAt());
  }

  @Test
  public void timeRegressionCannotPostponeWork() {
    PluginChangeCoalescer value = new PluginChangeCoalescer(100, 1000, 250);
    value.markDirty(100);
    value.markDirty(50);
    assertEquals(100, value.nextDelayMillis(50));
    assertTrue(value.beginScan(200));
  }

  @Test
  public void deadlineArithmeticSaturatesWithoutWrappingNegative() {
    PluginChangeCoalescer value = new PluginChangeCoalescer(100, 1000, 250);
    value.markDirty(Long.MAX_VALUE - 50);
    assertEquals(Long.MAX_VALUE, value.deadlineAt());
    assertEquals(50, value.nextDelayMillis(Long.MAX_VALUE - 50));
    assertTrue(value.beginScan(Long.MAX_VALUE));
  }

  @Test
  public void incompatibleDeadlineAndIntervalAreRejected() {
    try {
      new PluginChangeCoalescer(10, 100, 101);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      new PluginChangeCoalescer(101, 100, 0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void idleStateDoesNotRequestPolling() {
    PluginChangeCoalescer value = new PluginChangeCoalescer();
    assertEquals(-1, value.nextDelayMillis(0));
    assertEquals(-1, value.nextDelayMillis(100000));
    assertFalse(value.beginScan(100000));
    assertFalse(value.hasPending());
  }
}
