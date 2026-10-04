package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PermissionModeTest {
  @Test
  public void preservesSupportedChoices() {
    for (String value : new String[] {"read-only", "workspace-write", "danger-full-access"})
      assertEquals(value, PermissionMode.normalize(value));
  }

  @Test
  public void normalizesHistoricalAndInvalidValues() {
    for (String value : new String[] {null, "", "default", "unknown", "READ-ONLY", "read-only\n"})
      assertEquals("danger-full-access", PermissionMode.normalize(value));
  }
}
