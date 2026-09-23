package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class MaintenanceErrorTextTest {
    @Test public void retentionAndRecoveryCodesExplainNextAction() {
        assertTrue(MaintenanceErrorText.render("RUNTIME_RETENTION_LIMIT").contains("保留数据"));
        assertTrue(MaintenanceErrorText.render("RECOVERY_PENDING").contains("恢复中断维护"));
        assertTrue(MaintenanceErrorText.render("RUNTIME_RETENTION_LIMIT").contains("RUNTIME_RETENTION_LIMIT"));
    }

    @Test public void unknownTextIsNotRewritten() {
        assertEquals("plugin supplied detail", MaintenanceErrorText.render("plugin supplied detail"));
    }
}
