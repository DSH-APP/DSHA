package com.deepseekharness.app.vscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.util.ApplicationOwner;
import com.deepseekharness.app.util.OneShotLaunchAuthority;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Executes the current owner/manager APIs; Android framework construction is not acceptance. */
public final class VirtualScreenManagerOwnershipTest {
  private static final String TICKET = "0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String COMMAND = "fixed managed launch";

  private static final class Effects implements VirtualScreenManager.Visuals {
    final List<Long> stopped = new ArrayList<>();

    public void present() {}

    public synchronized void stopped(long fence) {
      stopped.add(fence);
    }
  }

  private static final class ApplicationFixture extends DshaApp {}

  private static final class WrapperFixture extends ContextWrapper {
    Context application, base;

    private WrapperFixture() {
      super(null);
    }

    @Override
    public Context getApplicationContext() {
      return application;
    }

    @Override
    public Context getBaseContext() {
      return base;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T withoutAndroidConstructor(Class<T> type) throws Exception {
    Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
    Field value = unsafeType.getDeclaredField("theUnsafe");
    value.setAccessible(true);
    return (T) unsafeType.getMethod("allocateInstance", Class.class).invoke(value.get(null), type);
  }

  private static ApplicationFixture application() throws Exception {
    ApplicationFixture app = withoutAndroidConstructor(ApplicationFixture.class);
    Field owner = DshaApp.class.getDeclaredField("virtualScreenOwner");
    owner.setAccessible(true);
    owner.set(app, new ApplicationOwner<VirtualScreenManager>());
    return app;
  }

  private static Object field(VirtualScreenManager manager, String name) throws Exception {
    Field value = VirtualScreenManager.class.getDeclaredField(name);
    value.setAccessible(true);
    return value.get(manager);
  }

  private static void shutdown(VirtualScreenManager manager) throws Exception {
    ((ScheduledExecutorService) field(manager, "WORKER")).shutdownNow();
  }

  @Test
  public void actualApplicationFactorySharesOwnerAcrossWrappersAndRejectsMissingOrCyclicRoots()
      throws Exception {
    ApplicationFixture first = application(), second = application();
    VirtualScreenManager one = VirtualScreenManager.from(first),
        two = VirtualScreenManager.from(second);
    try {
      WrapperFixture wrapper = withoutAndroidConstructor(WrapperFixture.class);
      wrapper.application = first;
      assertSame(one, VirtualScreenManager.from(wrapper));
      assertSame(one, first.virtualScreenManager());
      assertNotSame(one, two);
      assertSame(first, field(one, "context"));
      assertNotSame(wrapper, field(one, "context"));
      assertThrows(IllegalStateException.class, () -> VirtualScreenManager.from(null));
      WrapperFixture cycle = withoutAndroidConstructor(WrapperFixture.class);
      cycle.base = cycle;
      assertThrows(IllegalStateException.class, () -> VirtualScreenManager.from(cycle));
    } finally {
      shutdown(one);
      shutdown(two);
    }
  }

  @Test
  public void ticketsLocksClockAndNodeCacheAreOwnedByTheirManager() throws Exception {
    VirtualScreenManager one = new VirtualScreenManager(null, () -> 100, new Effects());
    VirtualScreenManager two = new VirtualScreenManager(null, () -> 200, new Effects());
    try {
      assertNotSame(field(one, "LOCK"), field(two, "LOCK"));
      assertNotSame(field(one, "ACTIONS"), field(two, "ACTIONS"));
      assertNotSame(field(one, "WORKER"), field(two, "WORKER"));
      assertNotSame(field(one, "accessibility"), field(two, "accessibility"));
      assertNotSame(field(one, "BRIDGE_AUTHORITY"), field(two, "BRIDGE_AUTHORITY"));
      AtomicLong epoch = (AtomicLong) field(one, "LIFECYCLE_EPOCH");
      epoch.set(7);
      OneShotLaunchAuthority authority = (OneShotLaunchAuthority) field(one, "ADB_LAUNCH");
      assertTrue(authority.issue(TICKET, COMMAND, 7, 100, 10000));
      assertEquals(TICKET, one.adbLaunchTicketFor(COMMAND));
      assertEquals("", two.adbLaunchTicketFor(COMMAND));
      assertFalse(two.authorizeAdbLaunchPlan(TICKET, COMMAND));
      assertTrue(one.authorizeAdbLaunchPlan(TICKET, COMMAND));
      assertFalse(two.commitAdbLaunch(TICKET));
      assertTrue(one.commitAdbLaunch(TICKET));
      assertEquals(0, two.epoch());
    } finally {
      shutdown(one);
      shutdown(two);
    }
  }

  @Test
  public void delayedRevokeCleanupAndCallbacksCannotCloseAnotherOwner() throws Exception {
    Effects first = new Effects(), second = new Effects();
    VirtualScreenManager one = new VirtualScreenManager(null, () -> 100, first);
    VirtualScreenManager two = new VirtualScreenManager(null, () -> 100, second);
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        drained = new CountDownLatch(1);
    ScheduledExecutorService worker = (ScheduledExecutorService) field(one, "WORKER");
    try {
      worker.execute(
          () -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException cancelled) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      one.revoke();
      assertEquals(1, one.epoch());
      assertEquals(0, two.epoch());
      assertTrue(second.stopped.isEmpty());
      worker.execute(drained::countDown);
      release.countDown();
      assertTrue(drained.await(2, TimeUnit.SECONDS));
      assertEquals(List.of(2L), first.stopped);
      assertTrue(second.stopped.isEmpty());
      assertEquals(0, two.epoch());
    } finally {
      release.countDown();
      shutdown(one);
      shutdown(two);
    }
  }
}
