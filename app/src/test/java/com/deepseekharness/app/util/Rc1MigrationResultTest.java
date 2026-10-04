package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.IOException;
import java.util.Map;
import static org.junit.Assert.*;

public class Rc1MigrationResultTest {
  @Test
  public void parsesExactRecordWithBothLineEndings() throws Exception {
    assertTrue(
        Rc1MigrationResult.allowsStart(
            Rc1MigrationResult.parse(
                "notice\r\nDSHA_RC1_MIGRATION={\"status\":\"prepared\",\"protectionComplete\":true}\r\n")));
    assertTrue(
        Rc1MigrationResult.allowsStart(
            Rc1MigrationResult.parse(
                "DSHA_RC1_MIGRATION={\"status\":\"already\",\"protectionComplete\":true}\n")));
  }

  @Test
  public void statusTextCannotReplaceProtection() throws Exception {
    assertFalse(Rc1MigrationResult.allowsStart(Map.of("status", "prepared")));
    assertFalse(
        Rc1MigrationResult.allowsStart(Map.of("status", "failed", "protectionComplete", true)));
    assertFalse(Rc1MigrationResult.allowsStart(Map.of("status", "skipped", "reason", "ERROR")));
    assertTrue(
        Rc1MigrationResult.allowsStart(Map.of("status", "skipped", "reason", "DSH_MISSING")));
    assertThrows(
        IOException.class, () -> Rc1MigrationResult.parse("ERROR: {\"status\":\"prepared\"}"));
    assertThrows(
        IOException.class,
        () -> Rc1MigrationResult.parse("DSHA_RC1_MIGRATION={}\nDSHA_RC1_MIGRATION={}"));
  }

  @Test
  public void routineReceiptsAreQuietButIncompleteProtectionRemainsVisible() {
    assertTrue(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of("status", "already", "protectionComplete", true)));
    assertTrue(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of("status", "prepared", "protectionComplete", true)));
    assertTrue(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of("status", "committed", "sourcePreserved", true, "settingsImported", true)));
    assertFalse(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of("status", "already", "protectionComplete", false)));
    assertFalse(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of("status", "committed", "sourcePreserved", false, "settingsImported", true)));
    assertFalse(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of("status", "pending", "sourcePreserved", true, "settingsImported", false)));
    assertFalse(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of("status", "failed", "protectionComplete", true)));
  }
}
