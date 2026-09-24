package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.IOException;
import java.util.Map;
import static org.junit.Assert.*;

public class Rc1MigrationResultTest {
    @Test public void parsesExactRecordWithBothLineEndings()throws Exception {
        assertTrue(Rc1MigrationResult.allowsStart(Rc1MigrationResult.parse("notice\r\nDSHA_RC1_MIGRATION={\"status\":\"prepared\",\"protectionComplete\":true}\r\n")));
        assertTrue(Rc1MigrationResult.allowsStart(Rc1MigrationResult.parse("DSHA_RC1_MIGRATION={\"status\":\"already\",\"protectionComplete\":true}\n")));
    }
    @Test public void statusTextCannotReplaceProtection()throws Exception {
        assertFalse(Rc1MigrationResult.allowsStart(Map.of("status","prepared")));
        assertFalse(Rc1MigrationResult.allowsStart(Map.of("status","failed","protectionComplete",true)));
        assertFalse(Rc1MigrationResult.allowsStart(Map.of("status","skipped","reason","ERROR")));
        assertTrue(Rc1MigrationResult.allowsStart(Map.of("status","skipped","reason","DSH_MISSING")));
        assertThrows(IOException.class,()->Rc1MigrationResult.parse("ERROR: {\"status\":\"prepared\"}"));
        assertThrows(IOException.class,()->Rc1MigrationResult.parse("DSHA_RC1_MIGRATION={}\nDSHA_RC1_MIGRATION={}"));
    }
}
