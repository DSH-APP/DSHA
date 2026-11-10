package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaintenanceRegistrationTest {
  @Test
  public void successIsReportedAsOk() {
    AtomicBoolean ran = new AtomicBoolean();
    String outcome = MaintenanceRegistration.attempt(() -> ran.set(true));
    assertEquals(MaintenanceRegistration.OK, outcome);
    assertTrue(ran.get());
  }

  @Test
  public void ioFailureIsDeferredWithBoundedReason() {
    String outcome =
        MaintenanceRegistration.attempt(
            () -> {
              throw new IOException("BUILTIN_REGISTER_TIMEOUT hostElapsedMs=240000");
            });
    assertTrue(outcome.startsWith(MaintenanceRegistration.DEFERRED + ":"));
    assertTrue(outcome.contains("BUILTIN_REGISTER_TIMEOUT"));
  }

  @Test
  public void longReasonIsTruncatedAndWhitespaceNormalized() {
    String outcome =
        MaintenanceRegistration.attempt(
            () -> {
              throw new IOException(("line one\nline two " + "x".repeat(300)));
            });
    assertTrue(outcome.startsWith(MaintenanceRegistration.DEFERRED + ":"));
    assertFalse(outcome.contains("\n"));
    assertTrue(outcome.length() <= MaintenanceRegistration.DEFERRED.length() + 2 + 121);
  }

  @Test
  public void interruptionIsDeferredAndKeepsInterruptFlag() {
    Thread.interrupted();
    String outcome =
        MaintenanceRegistration.attempt(
            () -> {
              throw new InterruptedException("stop");
            });
    assertEquals(MaintenanceRegistration.DEFERRED + ":INTERRUPTED", outcome);
    assertTrue(Thread.interrupted());
  }

  @Test
  public void nullMessageFallsBackToExceptionType() {
    assertTrue(
        MaintenanceRegistration.reason(new IllegalStateException()).contains("IllegalState"));
  }
}
