package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnvironmentTaskGateTest {
  @Test
  public void unknownProcessReturnsWithoutWaitingButKeepsExactLeaseUntilExit() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean exited =
        new java.util.concurrent.atomic.AtomicBoolean();
    Process process =
        new Process() {
          public int exitValue() {
            if (!exited.get()) throw new IllegalThreadStateException();
            return 0;
          }

          public int waitFor() {
            throw new AssertionError("no worker blocking wait");
          }

          public void destroy() {
            throw new AssertionError("no signalling");
          }

          public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
          }

          public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
          }

          public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
          }
        };
    long start = System.nanoTime();
    try (var lease = EnvironmentTaskGate.tryAcquire("adb")) {
      lease.run(
          () -> {
            EnvironmentTaskGate.retainCurrentUntilExit(process);
            assertFalse(EnvironmentTaskGate.canExecuteCurrentThread());
            assertThrows(
                AdbEnvironmentTask.Busy.class,
                () -> AdbEnvironmentTask.run("nested", () -> false, () -> null));
            return null;
          });
    }
    assertTrue((System.nanoTime() - start) / 1_000_000 < 500);
    assertTrue(EnvironmentTaskGate.isBusy());
    assertNull(EnvironmentTaskGate.tryAcquire("install"));
    exited.set(true);
    for (int i = 0; i < 40 && EnvironmentTaskGate.isBusy(); i++) Thread.sleep(50);
    assertFalse(EnvironmentTaskGate.isBusy());
  }

  @Test
  public void leaseAcquiredBeforeWorkerBlocksAllOtherTasks() throws Exception {
    try (EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("install")) {
      assertNotNull(lease);
      assertNull(EnvironmentTaskGate.tryAcquire("backup"));
      assertFalse(EnvironmentTaskGate.ownsCurrentThread());
      java.util.concurrent.atomic.AtomicBoolean owned =
          new java.util.concurrent.atomic.AtomicBoolean();
      Thread worker =
          new Thread(
              () -> {
                try {
                  lease.run(
                      () -> {
                        owned.set(EnvironmentTaskGate.ownsCurrentThread());
                        return null;
                      });
                } catch (Exception e) {
                  throw new AssertionError(e);
                }
              });
      worker.start();
      worker.join();
      assertTrue(owned.get());
      assertFalse(EnvironmentTaskGate.ownsCurrentThread());
      assertNull(EnvironmentTaskGate.tryAcquire("maintenance"));
    }
    assertFalse(EnvironmentTaskGate.isBusy());
  }

  @Test
  public void exceptionClearsThreadBindingAndFinallyReleasesLease() {
    EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("restore");
    try (lease) {
      assertThrows(
          java.io.IOException.class,
          () ->
              lease.run(
                  () -> {
                    throw new java.io.IOException("fixture");
                  }));
      assertFalse(EnvironmentTaskGate.ownsCurrentThread());
      assertTrue(EnvironmentTaskGate.isBusy());
    }
    assertFalse(EnvironmentTaskGate.isBusy());
    assertThrows(IllegalStateException.class, () -> lease.run(() -> null));
  }

  @Test
  public void cannotReleaseOrReuseWhileWorkerRunning() throws Exception {
    try (EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("backup")) {
      lease.run(
          () -> {
            assertThrows(IllegalStateException.class, lease::close);
            assertThrows(IllegalStateException.class, () -> lease.run(() -> null));
            assertTrue(EnvironmentTaskGate.ownsCurrentThread());
            return null;
          });
    }
  }

  @Test
  public void oneAtomicWinnerAmongDifferentTaskTypes() throws Exception {
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<EnvironmentTaskGate.Lease> winner =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicInteger count =
        new java.util.concurrent.atomic.AtomicInteger();
    Thread[] threads = new Thread[20];
    for (int i = 0; i < threads.length; i++) {
      threads[i] =
          new Thread(
              () -> {
                try {
                  go.await();
                  EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("task");
                  if (lease != null) {
                    count.incrementAndGet();
                    winner.set(lease);
                  }
                } catch (InterruptedException e) {
                  throw new AssertionError(e);
                }
              });
      threads[i].start();
    }
    go.countDown();
    for (Thread thread : threads) thread.join();
    try (EnvironmentTaskGate.Lease lease = winner.get()) {
      assertEquals(1, count.get());
    }
    assertFalse(EnvironmentTaskGate.isBusy());
  }
}
