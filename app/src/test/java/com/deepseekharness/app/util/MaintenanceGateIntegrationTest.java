package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class MaintenanceGateIntegrationTest {
  @Test
  public void detachedGuestBlocksMaintenanceUntilItsOriginalTokenCloses() throws Exception {
    MaintenanceGate gate = new MaintenanceGate();
    RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
    RealPorts ports = new RealPorts(registry);
    RuntimeTaskRegistry.Token guest = registry.begin(true, "guest");
    try {
      assertThrows(
          IOException.class,
          () ->
              gate.exclusive(
                  ports,
                  () -> {
                    throw new AssertionError("Must not enter with a detached guest");
                  }));
      assertEquals(1, registry.count());
      assertFalse(gate.isOwner());
      assertFalse(gate.isExclusive());
      assertFalse(EnvironmentTaskGate.isBusy());
    } finally {
      guest.close();
    }
    assertEquals(
        "verified",
        gate.exclusive(
            ports,
            () -> {
              assertTrue(gate.isOwner());
              return "verified";
            }));
    assertEquals(0, registry.count());
  }

  @Test
  public void maintenanceFenceRejectsForeignAndDetachedStarts() throws Exception {
    MaintenanceGate gate = new MaintenanceGate();
    RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
    RealPorts ports = new RealPorts(registry);
    gate.exclusive(
        ports,
        () -> {
          AtomicReference<Throwable> foreign = new AtomicReference<>();
          CountDownLatch attempted = new CountDownLatch(1);
          Thread other =
              new Thread(
                  () -> {
                    try {
                      registry.begin(false, "foreign");
                    } catch (Throwable error) {
                      foreign.set(error);
                    } finally {
                      attempted.countDown();
                    }
                  });
          other.start();
          attempted.await();
          other.join();
          assertTrue(foreign.get() instanceof IllegalStateException);
          assertThrows(IllegalStateException.class, () -> registry.begin(true, "detached"));
          assertNotNull(gate.archiveLock());
          assertTrue(gate.isOwner());
          return null;
        });
    assertFalse(EnvironmentTaskGate.isBusy());
    try (RuntimeTaskRegistry.Token later = registry.begin(true, "later")) {
      assertEquals(1, registry.count());
    }
  }

  @Test
  public void cancellationDuringFenceWaitKeepsGuestAndReleasesTicket() throws Exception {
    MaintenanceGate gate = new MaintenanceGate();
    RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
    RealPorts ports = new RealPorts(registry);
    ports.interruptPause = true;
    RuntimeTaskRegistry.Token guest = registry.begin(true, "guest");
    try {
      assertThrows(InterruptedException.class, () -> gate.exclusive(ports, () -> null));
      assertEquals(1, registry.count());
      assertNull(registry.tryEnterMaintenance());
      assertFalse(gate.isOwner());
      assertFalse(gate.isExclusive());
      assertFalse(EnvironmentTaskGate.isBusy());
    } finally {
      guest.close();
    }
  }

  @Test
  public void operationFailureReleasesOwnerFenceAndTicket() throws Exception {
    MaintenanceGate gate = new MaintenanceGate();
    RuntimeTaskRegistry registry = new RuntimeTaskRegistry();
    RealPorts ports = new RealPorts(registry);
    assertThrows(
        IOException.class,
        () ->
            gate.exclusive(
                ports,
                () -> {
                  assertTrue(gate.isOwner());
                  throw new IOException("synthetic transaction failure");
                }));
    assertFalse(gate.isOwner());
    assertFalse(gate.isExclusive());
    assertFalse(EnvironmentTaskGate.isBusy());
    assertEquals(0, registry.count());
    try (RuntimeTaskRegistry.Maintenance later = registry.tryEnterMaintenance()) {
      assertNotNull(later);
    }
  }

  private static final class RealPorts implements MaintenanceGate.Ports {
    private final RuntimeTaskRegistry registry;
    long elapsed;
    boolean interruptPause;

    RealPorts(RuntimeTaskRegistry registry) {
      this.registry = registry;
    }

    @Override
    public boolean ownsExecutionTicket() {
      return EnvironmentTaskGate.ownsCurrentThread();
    }

    @Override
    public MaintenanceGate.Ticket acquireExecutionTicket(String kind) {
      EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire(kind);
      if (lease == null) return null;
      return new MaintenanceGate.Ticket() {
        @Override
        public <T> T run(MaintenanceGate.Operation<T> operation) throws Exception {
          return lease.run(operation::run);
        }

        @Override
        public void close() {
          lease.close();
        }
      };
    }

    @Override
    public MaintenanceGate.Scope beginWork() {
      RuntimeTaskRegistry.Token token = registry.begin(false, "host data");
      return token::close;
    }

    @Override
    public void stopTerminals() {}

    @Override
    public void stopWeb() {}

    @Override
    public MaintenanceGate.Scope tryEnterMaintenance() {
      RuntimeTaskRegistry.Maintenance fence = registry.tryEnterMaintenance();
      return fence == null ? null : fence::close;
    }

    @Override
    public void confirmOtherProcesses() {}

    @Override
    public void releaseRecoveryTools() {}

    @Override
    public long elapsedRealtime() {
      return elapsed;
    }

    @Override
    public void pause(long millis) throws InterruptedException {
      if (interruptPause) throw new InterruptedException("cancelled");
      elapsed += millis;
    }
  }
}
