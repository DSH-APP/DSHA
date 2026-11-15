package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/** 维护空间预估：正常值保守、负数按 0、极端值饱和而不是回绕成负数。 */
public final class MaintenanceSpaceRequirementTest {
  @Test
  public void preflightCountsTwoDataCopiesAndFixedHeadroom() {
    assertEquals(
        100L + 2 * 50L + MaintenanceSpaceRequirement.HEADROOM,
        MaintenanceSpaceRequirement.preflight(100L, 50L));
    assertEquals(
        MaintenanceSpaceRequirement.HEADROOM, MaintenanceSpaceRequirement.preflight(0L, 0L));
  }

  @Test
  public void negativeInputsNeverReduceTheRequirement() {
    assertEquals(
        MaintenanceSpaceRequirement.HEADROOM, MaintenanceSpaceRequirement.preflight(-1L, -1L));
  }

  @Test
  public void overflowSaturatesInsteadOfWrapping() {
    assertEquals(Long.MAX_VALUE, MaintenanceSpaceRequirement.preflight(Long.MAX_VALUE, 10L));
    assertEquals(
        Long.MAX_VALUE, MaintenanceSpaceRequirement.snapshotReady(0L, Long.MAX_VALUE / 2 + 1));
    assertEquals(Long.MAX_VALUE, MaintenanceSpaceRequirement.snapshotReady(Long.MAX_VALUE, 0L));
  }

  @Test
  public void snapshotReadyUsesTheSameConservativeFormula() {
    assertEquals(
        3L + 2 * 4L + MaintenanceSpaceRequirement.HEADROOM,
        MaintenanceSpaceRequirement.snapshotReady(3L, 4L));
  }
}
