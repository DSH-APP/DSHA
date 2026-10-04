package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class MaintenanceGateTest {
  @Test
  public void exclusiveOrdersStopsBeforeFenceAndWork() throws Exception {
    MaintenanceGate gate = new MaintenanceGate();
    FakePorts ports = new FakePorts();
    String value =
        gate.exclusive(
            ports,
            () -> {
              assertTrue(gate.isOwner());
              ports.events.add("operation");
              return "ok";
            });
    assertEquals("ok", value);
    assertEquals(
        List.of(
            "ticket",
            "terminals",
            "web",
            "fence",
            "sessions",
            "work",
            "operation",
            "work-close",
            "recovery-release",
            "fence-close",
            "ticket-close"),
        ports.events);
    assertFalse(gate.isOwner());
    assertFalse(gate.isExclusive());
  }

  @Test
  public void snapshotCannotUpgradeWhileHoldingArchiveLock() throws Exception {
    MaintenanceGate gate = new MaintenanceGate();
    FakePorts ports = new FakePorts();
    gate.snapshot(
        ports,
        () -> {
          try {
            gate.exclusive(ports, () -> null);
            throw new AssertionError("Expected nested maintenance rejection");
          } catch (IOException expected) {
            assertFalse(gate.isOwner());
          }
          return null;
        });
    assertFalse(ports.events.contains("terminals"));
    assertFalse(gate.isExclusive());
  }

  @Test
  public void failedStopAndUnknownFenceKeepOperationClosed() throws Exception {
    MaintenanceGate gate = new MaintenanceGate();
    FakePorts ports = new FakePorts();
    ports.failWeb = true;
    try {
      gate.exclusive(ports, () -> null);
      throw new AssertionError("Expected stop failure");
    } catch (IOException expected) {
      assertEquals("WEB_UNKNOWN", expected.getMessage());
    }
    assertFalse(gate.isOwner());
    assertFalse(gate.isExclusive());
    assertFalse(ports.events.contains("operation"));

    ports = new FakePorts();
    ports.fenceAvailable = false;
    try {
      gate.exclusive(
          ports,
          () -> {
            throw new AssertionError("Must not run");
          });
      throw new AssertionError("Expected fence failure");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Web"));
    }
    assertFalse(gate.isOwner());
    assertFalse(gate.isExclusive());
    assertTrue(ports.elapsed >= 3000);
  }

  private static final class FakePorts implements MaintenanceGate.Ports {
    final List<String> events = new ArrayList<>();
    boolean ticketOwner, failWeb, fenceAvailable = true;
    long elapsed;

    @Override
    public boolean ownsExecutionTicket() {
      return ticketOwner;
    }

    @Override
    public MaintenanceGate.Ticket acquireExecutionTicket(String kind) {
      events.add("ticket");
      return new MaintenanceGate.Ticket() {
        @Override
        public <T> T run(MaintenanceGate.Operation<T> operation) throws Exception {
          ticketOwner = true;
          try {
            return operation.run();
          } finally {
            ticketOwner = false;
          }
        }

        @Override
        public void close() {
          events.add("ticket-close");
        }
      };
    }

    @Override
    public MaintenanceGate.Scope beginWork() {
      events.add("work");
      return () -> events.add("work-close");
    }

    @Override
    public void stopTerminals() {
      events.add("terminals");
    }

    @Override
    public void stopWeb() throws IOException {
      events.add("web");
      if (failWeb) throw new IOException("WEB_UNKNOWN");
    }

    @Override
    public MaintenanceGate.Scope tryEnterMaintenance() {
      if (!fenceAvailable) return null;
      events.add("fence");
      return () -> events.add("fence-close");
    }

    @Override
    public void confirmOtherProcesses() {
      events.add("sessions");
    }

    @Override
    public void releaseRecoveryTools() {
      events.add("recovery-release");
    }

    @Override
    public long elapsedRealtime() {
      return elapsed;
    }

    @Override
    public void pause(long millis) {
      elapsed += millis;
    }
  }
}
