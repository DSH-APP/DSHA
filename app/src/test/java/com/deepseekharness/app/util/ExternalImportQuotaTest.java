package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ExternalImportQuotaTest {
  private static final long NOW = 10L * ExternalImportQuota.TTL_MILLIS;
  private static final long GIB = 1024L * 1024 * 1024;

  private static String name(int i) {
    return String.format("%08d-0000-0000-0000-000000000000-boot.img", i);
  }

  private static ExternalImportQuota.Entry fresh(int i, long size) {
    return new ExternalImportQuota.Entry(name(i), size, NOW - 1000, true);
  }

  @Test
  public void emptyDirectoryAllowsImport() {
    ExternalImportQuota.Plan plan = ExternalImportQuota.plan(List.of(), GIB, NOW);
    assertTrue(plan.allowed());
    assertTrue(plan.expired.isEmpty());
  }

  @Test
  public void expiredEntriesAreReclaimedAndNoLongerCount() {
    List<ExternalImportQuota.Entry> entries = new ArrayList<>();
    for (int i = 0; i < 4; i++)
      entries.add(
          new ExternalImportQuota.Entry(
              name(i), 2 * GIB, NOW - ExternalImportQuota.TTL_MILLIS, true));
    ExternalImportQuota.Plan plan = ExternalImportQuota.plan(entries, 4 * GIB, NOW);
    assertTrue(plan.allowed());
    assertEquals(4, plan.expired.size());
  }

  @Test
  public void freshEntriesAreNeverDeletedToMakeRoom() {
    List<ExternalImportQuota.Entry> entries = List.of(fresh(1, 3 * GIB), fresh(2, 3 * GIB));
    ExternalImportQuota.Plan plan = ExternalImportQuota.plan(entries, 3 * GIB, NOW);
    assertEquals("IMPORT_QUOTA_BYTES", plan.rejection);
    assertTrue(plan.expired.isEmpty());
    assertTrue(ExternalImportQuota.plan(entries, 2 * GIB, NOW).allowed());
  }

  @Test
  public void fileCountLimitApplies() {
    List<ExternalImportQuota.Entry> entries =
        List.of(fresh(1, 1), fresh(2, 1), fresh(3, 1), fresh(4, 1));
    assertEquals("IMPORT_QUOTA_FILES", ExternalImportQuota.plan(entries, 1, NOW).rejection);
  }

  @Test
  public void foreignEntriesAreIgnored() {
    List<ExternalImportQuota.Entry> entries =
        List.of(
            new ExternalImportQuota.Entry("adb-home", 0, 0, false),
            new ExternalImportQuota.Entry("notes.txt", 100 * GIB, 0, true));
    ExternalImportQuota.Plan plan = ExternalImportQuota.plan(entries, GIB, NOW);
    assertTrue(plan.allowed());
    assertTrue(plan.expired.isEmpty());
  }

  @Test
  public void nonRegularImportSlotFailsClosedWithoutDeletion() {
    List<ExternalImportQuota.Entry> entries =
        List.of(
            new ExternalImportQuota.Entry(name(1), 0, 0, false),
            new ExternalImportQuota.Entry(name(2), 1, 0, true));
    ExternalImportQuota.Plan plan = ExternalImportQuota.plan(entries, 1, NOW);
    assertEquals("IMPORT_SLOT_UNEXPECTED", plan.rejection);
    assertTrue(plan.expired.isEmpty());
  }

  @Test
  public void futureTimestampIsNotExpired() {
    assertFalse(ExternalImportQuota.expired(NOW + ExternalImportQuota.TTL_MILLIS * 2, NOW));
    assertTrue(ExternalImportQuota.expired(NOW - ExternalImportQuota.TTL_MILLIS, NOW));
  }

  @Test
  public void invalidIncomingSizeIsRejected() {
    assertEquals("IMPORT_SIZE", ExternalImportQuota.plan(List.of(), 0, NOW).rejection);
    assertEquals(
        "IMPORT_SIZE",
        ExternalImportQuota.plan(List.of(), ExternalImportQuota.MAX_TOTAL_BYTES + 1, NOW)
            .rejection);
  }

  @Test
  public void overflowSafeComparison() {
    List<ExternalImportQuota.Entry> entries = List.of(fresh(1, Long.MAX_VALUE));
    assertEquals("IMPORT_QUOTA_BYTES", ExternalImportQuota.plan(entries, 1, NOW).rejection);
  }
}
