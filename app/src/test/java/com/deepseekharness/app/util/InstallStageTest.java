package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class InstallStageTest {
  @Test
  public void idsPreserveAllHistoricalStepNumbers() {
    int expected = 1;
    for (var stage : InstallStage.ordered()) {
      assertEquals(expected++, stage.id());
      assertEquals(stage, InstallStage.fromId(stage.id()));
    }
    assertThrows(IllegalArgumentException.class, () -> InstallStage.fromId(0));
  }

  @Test
  public void stableFailurePreservesOriginalReasonButNotSecrets() {
    var failure =
        InstallFailure.from(
            InstallStage.TOOLS,
            new java.io.IOException("disk unavailable; DEEPSEEK_API_KEY=hidden-secret"));
    assertEquals("INSTALL_TOOLS_IO", failure.code());
    assertTrue(failure.reason().contains("disk unavailable"));
    assertFalse(failure.display().contains("hidden-secret"));
  }
}
