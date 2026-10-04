package com.deepseekharness.app.util;

import java.util.Map;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ColdRuntimeSelectionTest {
  @Test
  public void snapshotsOnlyOwnedKeysAndKeepsAbsence() throws Exception {
    var value =
        ColdRuntimeSelection.snapshot(
            Map.of("api_key", "secret", "container_runtime", "proroot", "allow_root_shell", true));
    assertEquals(Map.of("container_runtime", "proroot"), value);
    assertFalse(value.containsKey("proot_disable_seccomp"));
  }

  @Test
  public void currentReceiptMustStillOwnThisCommit() throws Exception {
    var before = Map.<String, Object>of("container_runtime", "proroot");
    assertEquals(
        before,
        ColdRuntimeSelection.rollback(before, Map.of("cold_runtime_root", "owned"), "owned"));
    try {
      ColdRuntimeSelection.rollback(before, Map.of("cold_runtime_root", "later"), "owned");
      fail();
    } catch (IOException expected) {
      assertEquals("COLD_SELECTION_CHANGED", expected.getMessage());
    }
  }

  @Test
  public void cannotRestoreUnrelatedOrUnrepresentablePreferences() throws Exception {
    try {
      ColdRuntimeSelection.rollback(
          Map.of("api_key", "cipher"), Map.of("cold_runtime_root", "owned"), "owned");
      fail();
    } catch (IOException expected) {
      assertEquals("COLD_SELECTION_CHANGED", expected.getMessage());
    }
    try {
      ColdRuntimeSelection.snapshot(Map.of("container_runtime", new Object()));
      fail();
    } catch (IOException expected) {
      assertEquals("COLD_SELECTION_TYPE", expected.getMessage());
    }
  }
}
