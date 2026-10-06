package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class MaintenanceErrorTextTest {
  @org.junit.Test
  public void longOutputShowsActualFailureRatherThanUnpackNoise() {
    String text =
        "Unpacking lib\n".repeat(100)
            + "ldconfig.real: cache rename failed: Read-only file system\n"
            + "Unpacking lib\n".repeat(100);
    String summary = MaintenanceErrorText.render(text);
    assertTrue(summary.contains("Read-only file system"));
    assertTrue(summary.length() <= 1200);
  }

  @Test
  public void retentionAndRecoveryCodesExplainNextAction() {
    assertTrue(MaintenanceErrorText.render("RUNTIME_RETENTION_LIMIT").contains("保留数据"));
    assertTrue(MaintenanceErrorText.render("RECOVERY_PENDING").contains("恢复中断维护"));
    assertTrue(
        MaintenanceErrorText.render("RUNTIME_RETENTION_LIMIT").contains("RUNTIME_RETENTION_LIMIT"));
  }

  @Test
  public void unknownTextIsNotRewritten() {
    assertEquals("plugin supplied detail", MaintenanceErrorText.render("plugin supplied detail"));
  }

  @Test
  public void webSignalDeniedExplainsSafeRetry() {
    String text = MaintenanceErrorText.render("WEB_PROCESS_SIGNAL_DENIED");
    assertTrue(text.contains("优雅退出") || text.contains("gracefully"));
    assertTrue(text.contains("原环境") || text.contains("original environment"));
  }
}
