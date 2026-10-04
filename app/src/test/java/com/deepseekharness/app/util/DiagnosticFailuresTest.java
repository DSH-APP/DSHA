package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticFailuresTest {
  @Test
  public void boundedHistoryKeepsFirstAndRedactsEachOperation() {
    DiagnosticFailures failures = new DiagnosticFailures();
    failures.add("stage", new IllegalStateException("password=private-value\nsecond line"));
    for (int i = 0; i < 12; i++)
      failures.add("record:" + i, new IllegalArgumentException("token=private-value"));
    assertEquals("stage", failures.first().operation());
    assertEquals(8, failures.snapshot().size());
    assertEquals("record:4", failures.snapshot().get(0).operation());
    assertTrue(failures.summary().contains("stage IllegalStateException"));
    assertFalse(failures.summary().contains("private-value"));
    assertFalse(failures.summary().contains("second line"));
    assertThrows(UnsupportedOperationException.class, () -> failures.snapshot().clear());
  }
}
