package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class ScheduledBackupOwnerTest {
  @Test
  public void stopBeforeQueuedPreparationPreventsSubmission() {
    var owner = new ScheduledBackupOwner<Object>();
    var params = new Object();
    var ticket = owner.begin(params);
    var queue = new ArrayDeque<Runnable>();
    var calls = new ArrayList<String>();
    queue.add(
        () ->
            owner.accept(
                ticket,
                () -> {
                  calls.add("export");
                  owner.submitted(ticket, "job-a");
                }));
    assertSame(ticket, owner.stop(params));
    queue.remove().run();
    assertTrue(calls.isEmpty());
    assertEquals("", owner.jobId(ticket));
  }

  @Test
  public void startedJobRetainsItsExactIdentityAfterStop() {
    var owner = new ScheduledBackupOwner<Object>();
    var params = new Object();
    var ticket = owner.begin(params);
    var calls = new ArrayList<String>();
    assertTrue(
        owner.accept(
            ticket,
            () -> {
              calls.add("export-a");
              owner.submitted(ticket, "job-a");
            }));
    var stopped = owner.stop(params);
    assertTrue(owner.owns(stopped, "job-a"));
    assertFalse(owner.owns(stopped, "unrelated-user-job"));
    if (owner.owns(stopped, "job-a")) calls.add("cancel-a");
    assertEquals(List.of("export-a", "cancel-a"), calls);
  }

  @Test
  public void lateWorkerStopAndFinishCannotAffectNextRequest() {
    var owner = new ScheduledBackupOwner<Object>();
    var p1 = new Object();
    var old = owner.begin(p1);
    owner.stop(p1);
    var p2 = new Object();
    var next = owner.begin(p2);
    owner.submitted(next, "job-new");
    assertFalse(owner.accept(old, () -> fail("late export")));
    assertNull(owner.stop(p1));
    assertFalse(owner.finish(old));
    assertTrue(owner.current(next));
    assertTrue(owner.owns(next, "job-new"));
    assertFalse(owner.owns(old, "job-new"));
  }

  @Test
  public void completionOrDestructionClosesOnlyCurrentSchedulerTicket() {
    var owner = new ScheduledBackupOwner<Object>();
    var first = owner.begin(new Object());
    assertNull(owner.begin(new Object()));
    assertTrue(owner.finish(first));
    assertFalse(owner.finish(first));
    var second = owner.begin(new Object());
    assertSame(second, owner.stopCurrent());
    assertFalse(owner.current(second));
    assertFalse(owner.accept(second, () -> fail("destroyed submission")));
  }

  @Test
  public void workerCannotMutateOrReadMainOwnership() throws Exception {
    var owner = new ScheduledBackupOwner<Object>();
    var ticket = owner.begin(new Object());
    var failure = new AtomicReference<Throwable>();
    Thread worker =
        new Thread(
            () -> {
              try {
                owner.submitted(ticket, "worker-id");
              } catch (Throwable expected) {
                failure.set(expected);
              }
            });
    worker.start();
    worker.join();
    assertTrue(failure.get() instanceof IllegalStateException);
    assertEquals("", owner.jobId(ticket));
  }
}
