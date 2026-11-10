package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class AccessibilityServiceLifecycleEventsTest {
  @Test
  public void realCallbackKindsHaveFixedSeparateServiceCodes() {
    var events = new AccessibilityServiceLifecycleEvents();
    long epoch = 0;
    for (var kind : AccessibilityServiceLifecycleEvents.Kind.values()) events.record(kind, ++epoch);
    var result = events.drainEvents();
    assertEquals(
        List.of(
            "ACCESSIBILITY_SERVICE_CONNECTED",
            "ACCESSIBILITY_SERVICE_UNBOUND",
            "ACCESSIBILITY_SERVICE_DESTROYED",
            "ACCESSIBILITY_SERVICE_INTERRUPTED"),
        result.stream().map(event -> event.kind().code()).toList());
    assertEquals(
        List.of(1L, 2L, 3L, 4L),
        result.stream().map(AccessibilityServiceLifecycleEvents.Event::serviceEpoch).toList());
    assertFalse(events.hasEvents());
    assertTrue(events.drainEvents().isEmpty());
  }

  @Test
  public void serviceFactsCannotAuthorizeBoundOrRepair() {
    var events = new AccessibilityServiceLifecycleEvents();
    var connection = new ScreenConnectionEpoch<Object>();
    var repair = new AccessibilityRepairState();
    events.record(AccessibilityServiceLifecycleEvents.Kind.CONNECTED, 1);
    assertNull(connection.snapshot());
    assertEquals("NOT_ATTEMPTED", repair.snapshot().code());
    Object service = new Object();
    connection.connect(service);
    var lease = connection.snapshot();
    events.record(AccessibilityServiceLifecycleEvents.Kind.INTERRUPTED, lease.epoch);
    events.record(AccessibilityServiceLifecycleEvents.Kind.UNBOUND, lease.epoch);
    assertTrue(connection.current(lease));
    assertEquals("NOT_ATTEMPTED", repair.snapshot().code());
  }

  @Test
  public void oldServiceDestructionKeepsOwnEpochAndCannotImpersonateReplacement() {
    var connection = new ScreenConnectionEpoch<Object>();
    var events = new AccessibilityServiceLifecycleEvents();
    Object old = new Object(), replacement = new Object();
    connection.connect(old);
    long first = connection.snapshot().epoch;
    events.record(AccessibilityServiceLifecycleEvents.Kind.CONNECTED, first);
    events.record(AccessibilityServiceLifecycleEvents.Kind.INTERRUPTED, first);
    assertEquals(first, connection.snapshot().epoch);
    assertNotNull(connection.snapshot());
    assertTrue(connection.disconnect(old));
    events.record(AccessibilityServiceLifecycleEvents.Kind.UNBOUND, first);
    assertNull(connection.snapshot());
    connection.connect(replacement);
    long latest = connection.snapshot().epoch;
    assertTrue(latest > first);
    events.record(AccessibilityServiceLifecycleEvents.Kind.CONNECTED, latest);
    assertFalse(connection.disconnect(old));
    events.record(AccessibilityServiceLifecycleEvents.Kind.DESTROYED, first);
    assertEquals(latest, connection.snapshot().epoch);
    assertSame(replacement, connection.snapshot().service);
    var records = events.drainEvents();
    assertEquals(first, records.get(2).serviceEpoch());
    assertEquals(latest, records.get(3).serviceEpoch());
    assertEquals(first, records.get(4).serviceEpoch());
  }

  @Test
  public void queueIsBoundedAndInvalidFactsAddNothing() {
    var events = new AccessibilityServiceLifecycleEvents();
    events.record(null, 1);
    events.record(AccessibilityServiceLifecycleEvents.Kind.CONNECTED, -1);
    assertFalse(events.hasEvents());
    for (int index = 0; index < 100; index++)
      events.record(AccessibilityServiceLifecycleEvents.Kind.INTERRUPTED, index);
    var result = events.drainEvents();
    assertEquals(32, result.size());
    assertEquals(68, result.get(0).serviceEpoch());
    assertEquals(99, result.get(31).serviceEpoch());
  }
}
