package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExternalTargetBindingTest {
  @Test
  public void identityPinRequiresAllFields() {
    ExternalTargetBinding binding = new ExternalTargetBinding("serial", "PJD110", "fingerprint", 1);
    assertTrue(binding.matches("serial", "PJD110", "fingerprint"));
    assertFalse(binding.matches("serial", "other", "fingerprint"));
    assertFalse(binding.matches("serial", "PJD110", "other"));
  }

  @Test
  public void wirelessBindingIsMarked() {
    assertTrue(new ExternalTargetBinding("192.168.1.5:5555", "PJD110", "fp", 1).wireless());
    assertFalse(new ExternalTargetBinding("serial", "PJD110", "fp", 1).wireless());
    org.junit.Assert.assertThrows(
        IllegalArgumentException.class,
        () -> new ExternalTargetBinding("127.0.0.1:5555", "PJD110", "fp", 1));
  }
}
