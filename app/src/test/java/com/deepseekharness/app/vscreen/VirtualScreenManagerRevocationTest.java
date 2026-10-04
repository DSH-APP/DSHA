package com.deepseekharness.app.vscreen;

import com.deepseekharness.app.util.OneShotLaunchAuthority;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real manager admission/cleanup with its worker held; no device channel or remote command runs. */
public final class VirtualScreenManagerRevocationTest {
  private static final String OLD = "0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String NEW = "fedcba9876543210fedcba9876543210fedcba9876543210";
  private static final String COMMAND = "fixed fake app_process launch";

  @Test
  public void revokeFencesTicketBeforeBlockedWorkerCanCleanUp() throws Exception {
    VirtualScreenManager manager =
        new VirtualScreenManager(
            null,
            () -> 100,
            new VirtualScreenManager.Visuals() {
              public void present() {}

              public void stopped(long fence) {}
            });
    AtomicLong epoch = field(manager, "LIFECYCLE_EPOCH", AtomicLong.class);
    OneShotLaunchAuthority authority = field(manager, "ADB_LAUNCH", OneShotLaunchAuthority.class);
    ScheduledExecutorService worker = field(manager, "WORKER", ScheduledExecutorService.class);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try {
      epoch.set(7);
      authority.cancel(7);
      assertTrue(authority.issue(OLD, COMMAND, 7, 100, 30_000));
      assertEquals(OLD, manager.adbLaunchTicketFor(COMMAND));
      assertTrue(manager.authorizeAdbLaunchPlan(OLD, COMMAND));
      worker.execute(
          () -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(entered.await(2, TimeUnit.SECONDS));

      // Old code only queued stopLocked: this commit succeeded until the worker resumed.
      manager.revoke();
      assertFalse(manager.commitAdbLaunch(OLD));
      assertEquals("", manager.adbLaunchTicketFor(COMMAND));

      // Simulate a later authorized generation before the old cleanup gets worker time.
      long next = epoch.incrementAndGet();
      setField(manager, "token", "new-generation-token");
      setField(manager, "activeEpoch", next);
      assertTrue(authority.issue(NEW, COMMAND, next, 100, 30_000));
      assertTrue(manager.authorizeAdbLaunchPlan(NEW, COMMAND));
      CountDownLatch drained = new CountDownLatch(1);
      worker.execute(drained::countDown);
      release.countDown();
      assertTrue(drained.await(2, TimeUnit.SECONDS));
      assertEquals("new-generation-token", field(manager, "token", String.class));
      assertTrue(manager.commitAdbLaunch(NEW));
    } finally {
      release.countDown();
      authority.cancel(7);
      authority.cancel(epoch.get());
      setField(manager, "token", "");
      setField(manager, "activeEpoch", -1L);
      epoch.set(0);
      worker.shutdownNow();
    }
  }

  private static <T> T field(VirtualScreenManager manager, String name, Class<T> type)
      throws Exception {
    Field field = VirtualScreenManager.class.getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(manager));
  }

  private static void setField(VirtualScreenManager manager, String name, Object value)
      throws Exception {
    Field field = VirtualScreenManager.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(manager, value);
  }
}
