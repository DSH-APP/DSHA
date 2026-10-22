package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.WebStartProtection;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real controller admission/recovery and task gates; no backend or device is launched. */
@RunWith(WebLifecycleStartProtectionTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = WebLifecycleResponsivenessTest.App.class,
    shadows = WebLifecycleResponsivenessTest.HostMetadata.class)
public final class WebLifecycleStartProtectionTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  private WebLifecycleResponsivenessTest f;
  private final AtomicBoolean serviceCurrent = new AtomicBoolean(true);
  private final Object service = new Object();

  @Before
  public void prepare() throws Exception {
    f = new WebLifecycleResponsivenessTest();
    f.create();
    assertTrue(new File(f.boot.getRootfsDir(), "root/.dsh").mkdirs());
    f.boot.ready = true;
    assertFalse(StartupRepairs.pending(f.app));
    assertFalse(MaintenanceCoordinator.pending(f.controller));
  }

  @After
  public void close() throws Exception {
    if (f == null) return;
    f.state.startProtection.cancelAll();
    // Release only admitted startup leases. Stop Futures deliberately remain unexecuted:
    // a real guest, screen channel or user tree must never be touched by this fixture.
    for (Runnable work : f.pending.queued)
      if (!(work instanceof java.util.concurrent.Future<?>)) work.run();
    f.pending.queued.clear();
    f.close();
    assertFalse(EnvironmentTaskGate.isBusy());
  }

  private WebStartProtection.Ticket ticket() {
    var ticket = f.state.startProtection.begin();
    assertNotNull(ticket);
    assertTrue(f.state.startProtection.promote(ticket.id(), service, serviceCurrent::get));
    return ticket;
  }

  private void finishSyntheticStop(boolean confirmed) {
    var stop = (WebLifecycleResponsivenessTest.Pending.Deferred) f.pending.queued.remove(0);
    f.state.lifecycle.finishStop(f.controller.getWebGeneration());
    f.state.lastStopResult =
        new WebStopCoordinator.Result(
            confirmed ? WebStopCoordinator.Status.STOPPED : WebStopCoordinator.Status.UNCONFIRMED,
            "fixture",
            "");
    f.state.lastStopError = "";
    stop.complete();
  }

  @Test
  public void promotionIsRequiredBeforeAnyReadinessIo() {
    var ticket = f.state.startProtection.begin();
    assertFalse(f.controller.startProtectedWeb(ticket, false, false, null));
    assertEquals(0, f.boot.reads.get());
    assertTrue(f.pending.queued.isEmpty());
  }

  @Test
  public void externalStopWinsDuringSlowProtectedPreflight() throws Exception {
    var ticket = ticket();
    f.boot.slow = true;
    AtomicBoolean accepted = new AtomicBoolean(true);
    Thread worker =
        new Thread(() -> accepted.set(f.controller.startProtectedWeb(ticket, false, false, null)));
    worker.start();
    assertTrue(f.boot.reading.await(2, TimeUnit.SECONDS));
    f.controller.stopWeb(x -> {});
    f.boot.release.countDown();
    worker.join(2000);
    assertFalse(worker.isAlive());
    assertFalse(accepted.get());
    assertFalse(f.state.startProtection.hasPreparing());
    assertTrue(f.controller.isStopping());
    assertEquals(1, f.pending.queued.size());
  }

  @Test
  public void serviceLossDuringSlowPreflightRejectsFinalAdmission() throws Exception {
    var ticket = ticket();
    f.boot.slow = true;
    AtomicBoolean accepted = new AtomicBoolean(true);
    Thread worker =
        new Thread(() -> accepted.set(f.controller.startProtectedWeb(ticket, false, false, null)));
    worker.start();
    assertTrue(f.boot.reading.await(2, TimeUnit.SECONDS));
    serviceCurrent.set(false);
    f.boot.release.countDown();
    worker.join(2000);
    assertFalse(worker.isAlive());
    assertFalse(accepted.get());
    assertFalse(f.controller.isStarting());
    assertTrue(f.pending.queued.isEmpty());
  }

  @Test
  public void repeatedExternalStopCancelsRecoveryWithoutANewGeneration() {
    var ticket = ticket();
    assertTrue(f.controller.startProtectedWeb(ticket, true, true, null));
    long stopped = f.controller.getWebGeneration();
    assertEquals(2, f.pending.queued.size());
    f.controller.stopWeb(x -> {});
    assertEquals(stopped, f.controller.getWebGeneration());
    assertFalse(f.state.startProtection.hasPreparing());
    finishSyntheticStop(true);
    f.pending.queued.remove(0).run();
    assertTrue(f.pending.queued.isEmpty());
    assertFalse(f.controller.isStarting());
  }

  @Test
  public void unconfirmedInternalStopCannotRestartEvenWithAValidTicket() {
    var ticket = ticket();
    assertTrue(f.controller.startProtectedWeb(ticket, true, true, null));
    finishSyntheticStop(false);
    f.pending.queued.remove(0).run();
    assertTrue(f.pending.queued.isEmpty());
    assertFalse(f.controller.isStarting());
    assertFalse(f.state.startProtection.hasPreparing());
    assertEquals(0, f.boot.reads.get());
  }

  @Test
  public void confirmedInternalStopTransfersTheSameTicketIntoANewStart() {
    var ticket = ticket();
    assertTrue(f.controller.startProtectedWeb(ticket, true, true, null));
    long stopped = f.controller.getWebGeneration();
    assertTrue(f.state.startProtection.hasPreparing());
    finishSyntheticStop(true);
    f.pending.queued.remove(0).run();
    assertTrue(f.controller.isStarting());
    assertTrue(f.controller.getWebGeneration() > stopped);
    assertFalse(f.state.startProtection.hasPreparing());
    assertEquals(1, f.pending.queued.size());
  }

  @Test
  public void serviceDestroyedAfterAdmissionFencesQueuedWorkerAndReleasesItsLease() {
    var ticket = ticket();
    assertTrue(f.controller.startProtectedWeb(ticket, false, false, null));
    long admitted = f.controller.getWebGeneration();
    long lost = f.state.startProtection.serviceDestroyed(service);
    assertEquals(admitted, lost);
    f.controller.stopWebIfUnchanged(lost);
    assertTrue(f.controller.isStopping());
    f.pending.queued.remove(0).run();
    assertFalse(f.controller.isStarting());
    assertFalse(EnvironmentTaskGate.isBusy());
    assertFalse(f.controller.lastStopResult().stopped());
    assertEquals(1, f.pending.queued.size());
  }

  @Test
  public void serviceDeathAfterClaimStillCreatesTheCurrentRuntimeStopFence() {
    var ticket = ticket();
    assertTrue(f.controller.startProtectedWeb(ticket, false, false, null));
    long admitted = f.controller.getWebGeneration();
    assertTrue(f.state.startProtection.claimQueuedStart(ticket, admitted));
    long lost = f.state.startProtection.serviceDestroyed(service);
    assertEquals(admitted, lost);
    f.controller.stopWebIfUnchanged(lost);
    assertTrue(f.controller.isStopping());
    assertFalse(f.state.lifecycle.isCurrent(admitted));
    f.pending.queued.remove(0).run();
    assertFalse(EnvironmentTaskGate.isBusy());
  }

  @Test
  public void staleServiceDeathCannotCancelANewerPendingRequestOrRevokeItsScreen() {
    long original = f.state.lifecycle.beginStart(false, false);
    f.state.lifecycle.finishStart(original);
    f.state.lifecycle.beginStop();
    f.state.lifecycle.finishStop(f.controller.getWebGeneration());
    var newer = ticket();
    f.controller.stopWebIfUnchanged(original);
    assertTrue(f.state.startProtection.canAdmit(newer));
    assertEquals(0, f.screens.revokes.get());
    assertTrue(f.pending.queued.isEmpty());
  }

  @Test
  public void stopRevokesOutsideLifecycleBeforeItsWorkerCanCloseChannels() throws Exception {
    var closes = new java.util.concurrent.atomic.AtomicInteger();
    var finished = new java.util.concurrent.CountDownLatch(1);
    var workerRef = new java.util.concurrent.atomic.AtomicReference<Thread>();
    WebLifecycleController.ScreenLifetime screens =
        new WebLifecycleController.ScreenLifetime() {
          @Override
          public void revoke() {
            assertFalse(
                "screen authority must not be acquired under lifecycle",
                Thread.holdsLock(f.state.lifecycle));
            Thread worker =
                new Thread(
                    () -> {
                      try {
                        f.pending.queued.get(0).run();
                      } finally {
                        finished.countDown();
                      }
                    });
            workerRef.set(worker);
            worker.start();
            try {
              assertFalse(
                  "worker must await revocation", finished.await(100, TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) {
              throw new AssertionError(interrupted);
            }
            assertEquals(0, closes.get());
            assertTrue(f.controller.isStopping());
          }

          @Override
          public void stop() {
            closes.incrementAndGet();
            throw new IllegalStateException("fixture stops before guest I/O");
          }
        };
    var field = HarnessController.class.getDeclaredField("web");
    field.setAccessible(true);
    field.set(f.controller, new WebLifecycleController(f.controller, f.state, screens));
    f.controller.stopWeb(x -> {});
    workerRef.get().join(2000);
    assertFalse(workerRef.get().isAlive());
    assertEquals(1, closes.get());
    assertFalse(f.controller.isStopping());
    assertFalse(f.controller.lastStopResult().stopped());
    f.pending.queued.clear();
  }

  @Test
  public void rejectedStopSubmissionRevokesAndKeepsAFailedProofWithoutStuckStopping()
      throws Exception {
    var rejecting =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
          @Override
          public java.util.concurrent.Future<?> submit(Runnable work) {
            throw new java.util.concurrent.RejectedExecutionException("fixture");
          }
        };
    try {
      var io = HarnessSessionState.class.getDeclaredField("io");
      io.setAccessible(true);
      io.set(f.state, rejecting);
      var field = HarnessController.class.getDeclaredField("web");
      field.setAccessible(true);
      field.set(f.controller, new WebLifecycleController(f.controller, f.state, f.screens));
      f.controller.stopWeb(x -> {});
      assertFalse(f.controller.isStopping());
      assertTrue(f.controller.isUserStopped());
      assertEquals(1, f.screens.revokes.get());
      assertFalse(f.controller.lastStopResult().stopped());
      assertEquals(WebStopCoordinator.Status.FAILED, f.controller.lastStopResult().status());
      assertTrue(f.state.stopTask.isDone());
      try {
        f.state.stopTask.get();
        fail("rejected Stop must retain a failed Future");
      } catch (java.util.concurrent.ExecutionException failure) {
        assertTrue(failure.getCause() instanceof java.util.concurrent.RejectedExecutionException);
        assertEquals("fixture", failure.getCause().getMessage());
      }
    } finally {
      rejecting.shutdownNow();
    }
  }
}
