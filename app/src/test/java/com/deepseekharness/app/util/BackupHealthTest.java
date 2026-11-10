package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.BackupHealth.Level;
import org.junit.Test;

public class BackupHealthTest {
  private static final long DAY = 24L * 60 * 60 * 1000;
  private static final long NOW = 1_800_000_000_000L;

  @Test
  public void runningWinsOverEverything() {
    assertEquals(Level.RUNNING, BackupHealth.evaluate(0, "DISK_FULL", true, true, NOW));
  }

  @Test
  public void pendingMaintenanceWinsOverFailure() {
    assertEquals(Level.PENDING, BackupHealth.evaluate(NOW, "DISK_FULL", false, true, NOW));
  }

  @Test
  public void failureIsReportedEvenWithOlderSuccess() {
    assertEquals(Level.FAILED, BackupHealth.evaluate(NOW - DAY, "DISK_FULL", false, false, NOW));
  }

  @Test
  public void nullOrEmptyErrorIsNotFailure() {
    assertEquals(Level.NEVER, BackupHealth.evaluate(0, null, false, false, NOW));
    assertEquals(Level.NEVER, BackupHealth.evaluate(0, "", false, false, NOW));
  }

  @Test
  public void staleBoundaryIsExclusive() {
    long limit = NOW - BackupHealth.STALE_DAYS * DAY;
    assertEquals(Level.PROTECTED, BackupHealth.evaluate(limit, "", false, false, NOW));
    assertEquals(Level.STALE, BackupHealth.evaluate(limit - 1, "", false, false, NOW));
  }

  @Test
  public void clockRollbackCountsAsFresh() {
    assertEquals(Level.PROTECTED, BackupHealth.evaluate(NOW + DAY, "", false, false, NOW));
    assertEquals(0, BackupHealth.ageDays(NOW + DAY, NOW));
  }

  @Test
  public void ageDaysUsesWholeDays() {
    assertEquals(0, BackupHealth.ageDays(0, NOW));
    assertEquals(9, BackupHealth.ageDays(NOW - 9 * DAY - 5, NOW));
  }

  @Test
  public void severityGroupsLevels() {
    assertEquals(0, BackupHealth.severity(Level.PROTECTED));
    assertEquals(0, BackupHealth.severity(Level.RUNNING));
    assertEquals(1, BackupHealth.severity(Level.STALE));
    assertEquals(1, BackupHealth.severity(Level.NEVER));
    assertEquals(2, BackupHealth.severity(Level.FAILED));
    assertEquals(2, BackupHealth.severity(Level.PENDING));
  }
}
