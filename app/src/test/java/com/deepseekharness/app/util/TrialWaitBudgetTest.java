package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrialWaitBudgetTest {
  @Test
  public void softNoticeIsNotTimeoutAndEachPhaseKeepsIndependentBudget() throws Exception {
    AtomicLong now = new AtomicLong(1000);
    var start = new TrialWaitBudget(TrialWaitBudget.Phase.STARTING, now::get);
    now.addAndGet(60_000);
    assertTrue(start.stillWaiting());
    start.check();
    now.addAndGet(540_000);
    assertTrue(assertThrows(IOException.class, start::check).getMessage().contains("STARTING"));
    var auth = new TrialWaitBudget(TrialWaitBudget.Phase.AUTHENTICATING, now::get);
    now.addAndGet(119_999);
    auth.check();
    now.incrementAndGet();
    assertThrows(IOException.class, auth::check);
  }
}
