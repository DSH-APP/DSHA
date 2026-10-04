package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class ApplicationServiceSlotTest {
  @Test
  public void applicationsDoNotShareServicesAndOldDestroyCannotDetachANewService() {
    ApplicationServiceSlot<Object> first = new ApplicationServiceSlot<>();
    ApplicationServiceSlot<Object> second = new ApplicationServiceSlot<>();
    Object old = new Object(), replacement = new Object();
    first.attach(old);
    assertNull(second.current());
    first.attach(replacement);
    first.release(old);
    assertSame(replacement, first.current());
    first.release(replacement);
    assertNull(first.current());
  }

  @Test
  public void missingServiceCannotOverwriteAValidRegistration() {
    ApplicationServiceSlot<Object> slot = new ApplicationServiceSlot<>();
    Object service = new Object();
    slot.attach(service);
    assertThrows(NullPointerException.class, () -> slot.attach(null));
    assertSame(service, slot.current());
  }
}
