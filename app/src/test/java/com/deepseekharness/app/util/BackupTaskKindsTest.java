package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackupTaskKindsTest {
  @Test
  public void translatedHistoricKindsHaveOneControlIdentityAndCurrentDisplay() {
    assertEquals(BackupTaskKinds.REBUILD, BackupTaskKinds.normalize("Rebuild environment"));
    assertEquals(BackupTaskKinds.REBUILD, BackupTaskKinds.normalize("重建环境"));
    assertTrue(BackupTaskKinds.is(BackupTaskKinds.FACTORY_RESET, "Format DSHA"));
    assertEquals("Rebuild environment", BackupTaskKinds.display(BackupTaskKinds.REBUILD, true));
    assertEquals("重建环境", BackupTaskKinds.display(BackupTaskKinds.REBUILD, false));
    assertEquals("unknown user text", BackupTaskKinds.normalize("unknown user text"));
    assertNull(BackupTaskKinds.display("unknown user text", true));
  }
}
