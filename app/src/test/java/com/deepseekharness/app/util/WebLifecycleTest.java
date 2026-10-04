package com.deepseekharness.app.util;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class WebLifecycleTest {
  @Test
  public void concurrentManualAndWatchdogStartHaveOneWinner() throws Exception {
    WebLifecycle gate = new WebLifecycle();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    FutureTask<Long> manual = request(gate, false, ready, go);
    FutureTask<Long> watchdog = request(gate, true, ready, go);
    assertTrue(ready.await(2, TimeUnit.SECONDS));
    go.countDown();
    long a = manual.get(2, TimeUnit.SECONDS);
    long b = watchdog.get(2, TimeUnit.SECONDS);
    assertTrue((a > 0) ^ (b > 0));
    assertTrue(gate.isStarting());
  }

  private FutureTask<Long> request(
      WebLifecycle gate, boolean automatic, CountDownLatch ready, CountDownLatch go) {
    FutureTask<Long> task =
        new FutureTask<>(
            () -> {
              ready.countDown();
              if (!go.await(2, TimeUnit.SECONDS)) throw new AssertionError("启动栅栏超时");
              return gate.beginStart(automatic, false);
            });
    Thread thread = new Thread(task);
    thread.setDaemon(true);
    thread.start();
    return task;
  }

  @Test
  public void stopInvalidatesQueuedStartAndBlocksWatchdogUntilManualStart() {
    WebLifecycle gate = new WebLifecycle();
    long old = gate.beginStart(false, false);
    long stop = gate.beginStop();
    assertEquals(stop, gate.beginStop());
    assertFalse(gate.isCurrent(old));
    assertFalse(gate.finishStart(old));
    assertFalse(gate.isStarting());
    assertTrue(gate.isStopping());
    assertEquals(-1, gate.beginStart(false, false));
    gate.finishStop(stop);
    assertFalse(gate.canAutoStart(false));
    assertEquals(-1, gate.beginStart(true, false));
    long next = gate.beginStart(false, true);
    assertTrue(next > stop);
    gate.finishStart(old);
    gate.finishStop(stop);
    assertTrue(gate.isStarting());
    assertTrue(gate.isCurrent(next));
  }

  @Test
  public void completionOrFailureReleasesStartAndSentinelSurvivesServiceRecreation() {
    WebLifecycle gate = new WebLifecycle();
    assertEquals(-1, gate.beginStart(true, true));
    long first = gate.beginStart(false, true);
    assertTrue(gate.finishStart(first));
    long restart = gate.beginStart(false, false);
    assertTrue(restart > first);
    assertFalse(gate.finishStart(first));
    assertTrue(gate.isStarting());
    assertTrue(gate.finishStart(restart));
    assertTrue(gate.canAutoStart(false));
    assertFalse(gate.canAutoStart(true));
  }
}
