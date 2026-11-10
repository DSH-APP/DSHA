package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Test;

/** Two independent facts are required: the exact process exited and the owning runner returned. */
public final class EnvironmentRetainedExitTest {
  private final List<Child> children = new ArrayList<>();

  private static final class Child extends Process {
    volatile int state; // 0 live, 1 unreadable, 2 exited
    final AtomicInteger probes = new AtomicInteger();
    final AtomicReference<Thread> observer = new AtomicReference<>();

    public int exitValue() {
      observer.set(Thread.currentThread());
      probes.incrementAndGet();
      if (state == 1) throw new SecurityException("unreadable exit evidence");
      if (state == 0) throw new IllegalThreadStateException();
      return 17;
    }

    public int waitFor() {
      throw new AssertionError("No unbounded process wait");
    }

    public void destroy() {
      throw new AssertionError("No signal or replay");
    }

    public java.io.InputStream getInputStream() {
      throw new AssertionError("No content read");
    }

    public java.io.InputStream getErrorStream() {
      throw new AssertionError("No content read");
    }

    public java.io.OutputStream getOutputStream() {
      throw new AssertionError("No process input");
    }
  }

  private Child child(int state) {
    Child result = new Child();
    result.state = state;
    children.add(result);
    return result;
  }

  private static void until(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue("bounded observer result", condition.getAsBoolean());
  }

  private static EnvironmentTaskGate.Lease retain(Child child) throws Exception {
    var lease = EnvironmentTaskGate.tryAcquire("retained-fixture");
    assertNotNull(lease);
    lease.run(
        () -> {
          EnvironmentTaskGate.retainCurrentUntilExit(child);
          return null;
        });
    lease.close();
    until(() -> child.probes.get() > 0);
    return lease;
  }

  @After
  public void releaseFixtureOnlyState() throws Exception {
    for (Child child : children) child.state = 2;
    for (Child child : children) {
      Thread thread = child.observer.get();
      if (thread != null && thread != Thread.currentThread()) thread.join(1500);
    }
    // The old production watcher intentionally strands its lease in the negative control.
    // Reset only this isolated test JVM after preserving that assertion failure.
    Field active = EnvironmentTaskGate.class.getDeclaredField("active");
    active.setAccessible(true);
    synchronized (EnvironmentTaskGate.class) {
      active.set(null, null);
    }
  }

  @Test
  public void transientExitExceptionCannotStrandTheExclusiveLeaseAfterConfirmedExit()
      throws Exception {
    Child child = child(1);
    retain(child);
    assertTrue(EnvironmentTaskGate.isBusy());
    child.state = 2;
    until(() -> !EnvironmentTaskGate.isBusy());
  }

  @Test
  public void interruptedObserverKeepsTheLeaseUntilExitThenReleasesIt() throws Exception {
    Child child = child(0);
    retain(child);
    Thread observer = child.observer.get();
    observer.interrupt();
    assertTrue(EnvironmentTaskGate.isBusy());
    child.state = 2;
    until(() -> !EnvironmentTaskGate.isBusy());
    observer.join(1000);
    assertTrue("observer interruption is preserved", observer.isInterrupted());
  }

  @Test
  public void repeatedUnknownAndLiveEvidenceBothKeepExclusiveOwnership() throws Exception {
    Child child = child(1);
    retain(child);
    until(() -> child.probes.get() >= 2);
    assertTrue(EnvironmentTaskGate.isBusy());
    assertNull(EnvironmentTaskGate.tryAcquire("backup"));
    child.state = 0;
    int before = child.probes.get();
    until(() -> child.probes.get() > before);
    assertTrue(EnvironmentTaskGate.isBusy());
    child.state = 2;
    until(() -> !EnvironmentTaskGate.isBusy());
  }

  @Test
  public void exitedProcessDoesNotReleaseAStillRunningLeaseOwner() throws Exception {
    Child child = child(2);
    var lease = EnvironmentTaskGate.tryAcquire("runner-fixture");
    CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();
    Thread runner =
        new Thread(
            () -> {
              try (lease) {
                lease.run(
                    () -> {
                      EnvironmentTaskGate.retainCurrentUntilExit(child);
                      entered.countDown();
                      finish.await();
                      return null;
                    });
              } catch (Throwable failure) {
                error.set(failure);
              }
            });
    runner.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Thread.sleep(50);
      assertTrue(EnvironmentTaskGate.isBusy());
      assertNull(EnvironmentTaskGate.tryAcquire("backup"));
    } finally {
      finish.countDown();
      runner.join(2000);
    }
    assertNull(error.get());
    until(() -> !EnvironmentTaskGate.isBusy());
  }

  @Test
  public void lateOldObserverCannotReleaseAReplacementExclusiveLease() throws Exception {
    Child child = child(0);
    retain(child);
    Field active = EnvironmentTaskGate.class.getDeclaredField("active");
    active.setAccessible(true);
    synchronized (EnvironmentTaskGate.class) {
      active.set(null, null);
    }
    try (var replacement = EnvironmentTaskGate.tryAcquire("new-owner")) {
      assertNotNull(replacement);
      child.state = 2;
      Thread oldObserver = child.observer.get();
      oldObserver.join(2000);
      assertFalse(oldObserver.isAlive());
      assertTrue(EnvironmentTaskGate.isBusy());
      assertEquals("new-owner", EnvironmentTaskGate.activeKind());
    }
  }

  @Test
  public void exactRetainedProcessCannotBeReplacedByAnotherProcess() throws Exception {
    Child original = child(0), another = child(2);
    var lease = retain(original);
    assertThrows(
        IllegalStateException.class,
        () ->
            lease.run(
                () -> {
                  EnvironmentTaskGate.retainCurrentUntilExit(another);
                  return null;
                }));
    assertTrue(EnvironmentTaskGate.isBusy());
    assertEquals(0, another.probes.get());
    original.state = 2;
    until(() -> !EnvironmentTaskGate.isBusy());
  }
}
