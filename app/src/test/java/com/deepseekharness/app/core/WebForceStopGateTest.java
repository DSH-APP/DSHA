package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebLifecycle;
import org.junit.Test;

public class WebForceStopGateTest {
  @Test
  public void escalationNeedsAFailedOrdinaryStopAndTheSameUserStopIntent() {
    WebLifecycle lifecycle = new WebLifecycle();
    long running = lifecycle.beginStart(false, false);
    var unconfirmed =
        new WebStopCoordinator.Result(
            WebStopCoordinator.Status.UNCONFIRMED, "WEB_PROCESS_EXIT_UNCONFIRMED", "pending");
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, unconfirmed, running));
    lifecycle.finishStart(running);
    long stopped = lifecycle.beginStop();
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, unconfirmed, stopped));
    lifecycle.finishStop(stopped);
    assertTrue(WebLifecycleController.forceStopAllowed(lifecycle, unconfirmed, stopped));
    assertFalse(
        WebLifecycleController.forceStopAllowed(
            lifecycle,
            new WebStopCoordinator.Result(WebStopCoordinator.Status.STOPPED, "WEB_STOPPED", ""),
            stopped));
    long next = lifecycle.beginStart(false, true);
    assertTrue(next > stopped);
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, unconfirmed, stopped));
    assertFalse(WebLifecycleController.forceStopAllowed(lifecycle, unconfirmed, next));
  }
}
