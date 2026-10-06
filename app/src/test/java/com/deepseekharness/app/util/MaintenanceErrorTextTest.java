package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class MaintenanceErrorTextTest {
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

  @Test
  public void longUnpackLogKeepsFailureAndDropsNoise() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 80; i++) sb.append("Unpacking lib").append(i).append(" ...\n");
    sb.append("ldconfig.real: Renaming of /etc/ld.so.cache~ failed: Read-only file system\n");
    sb.append("dpkg: error processing package libc-bin (--unpack)\n");
    String out = MaintenanceErrorText.render(sb.toString());
    assertTrue(out.contains("Read-only file system"));
    assertTrue(out.contains("error processing package libc-bin"));
    assertFalse(out.contains("Unpacking lib40"));
    assertTrue(out.length() < sb.length() / 2);
  }
}
