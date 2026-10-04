package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeTrialOutputPolicyTest {
  @Test
  public void onlyOwnedTrialPluginFailureStopsTheTrial() {
    assertTrue(
        RuntimeTrialOutputPolicy.ownedPluginFailure(
            "Error: failed to apply loader entry ab12cd34 (dsha-runtime-check): missing service"));
    assertTrue(
        RuntimeTrialOutputPolicy.ownedPluginFailure(
            "Error: failed to import loader entry ab12cd34 (dsha-runtime-check): module missing"));
    assertFalse(
        RuntimeTrialOutputPolicy.ownedPluginFailure(
            "Error: failed to apply loader entry ab12cd34 (@deepseek-ai/cordis-plugin-hmr): --expose-internals is required"));
    assertFalse(RuntimeTrialOutputPolicy.ownedPluginFailure("ordinary plugin output"));
    assertFalse(RuntimeTrialOutputPolicy.ownedPluginFailure(null));
    assertFalse(
        RuntimeTrialOutputPolicy.ownedPluginFailure(
            "a user quoted Error: failed to apply loader entry ab12 (dsha-runtime-check): text"));
    assertFalse(
        RuntimeTrialOutputPolicy.ownedPluginFailure(
            "Error: failed to import loader entry ab12 (dsha-runtime-check-extra): text"));
    assertFalse(
        RuntimeTrialOutputPolicy.ownedPluginFailure(
            "Error: failed to import loader entry ab12 (other): mentions dsha-runtime-check"));
    assertFalse(
        RuntimeTrialOutputPolicy.ownedPluginFailure(
            "Error: failed to import loader entry ab12 (dsha-runtime-check): text\nsecond output"));
    assertTrue(
        RuntimeTrialOutputPolicy.ownedPluginFailure(
            "\u001b[31mError: failed to import loader entry ab12 (dsha-runtime-check): missing\u001b[0m"));
  }
}
