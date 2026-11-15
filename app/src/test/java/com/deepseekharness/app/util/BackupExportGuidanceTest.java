package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** 导出拒绝原因：未知输入不升级为结论；普通导出与救援导出的维护提示不同。 */
public final class BackupExportGuidanceTest {
  private String previous;

  @Before
  public void setUp() {
    previous = UiText.language();
    UiText.setLanguage(UiLanguagePreference.ZH);
  }

  @After
  public void tearDown() {
    UiText.setLanguage(previous);
  }

  @Test
  public void classifyKnownAndUnknownRefusals() {
    assertEquals(BackupExportGuidance.Kind.BUSY, BackupExportGuidance.classify("BUSY"));
    assertEquals(
        BackupExportGuidance.Kind.MAINTENANCE_PENDING,
        BackupExportGuidance.classify("MAINTENANCE_PENDING"));
    assertEquals(BackupExportGuidance.Kind.UNKNOWN, BackupExportGuidance.classify("free text"));
    assertEquals(BackupExportGuidance.Kind.UNKNOWN, BackupExportGuidance.classify(null));
  }

  @Test
  public void maintenanceMessageDiffersForRescue() {
    String normal = BackupExportGuidance.message("MAINTENANCE_PENDING", false);
    String rescue = BackupExportGuidance.message("MAINTENANCE_PENDING", true);
    assertNotEquals(normal, rescue);
    assertTrue(normal.contains("恢复"));
    assertTrue(rescue.contains("救援导出仍可继续"));
  }

  @Test
  public void everyKindHasNonEmptyGuidanceWithoutCodes() {
    for (BackupExportGuidance.Kind kind : BackupExportGuidance.Kind.values()) {
      String message = BackupExportGuidance.message(kind.name(), false);
      assertFalse(kind.name(), message.isEmpty());
      assertFalse(kind.name(), message.contains(kind.name()));
    }
  }
}
