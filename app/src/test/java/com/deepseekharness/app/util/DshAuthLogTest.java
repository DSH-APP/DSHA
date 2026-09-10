package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class DshAuthLogTest {
    @Test public void redactsEveryPossibleReadBoundary() {
        String line = "中文 dsh web: http://127.0.0.1:3080/?token=" + "privateTOKEN".repeat(4) + "\n";
        for (int split = 0; split < line.length(); split++) {
            DshAuthLog log = new DshAuthLog();
            String safe = log.append(line.substring(0, split)) + log.append(line.substring(split)) + log.finish();
            assertFalse(safe, safe.contains("private")); assertFalse(safe, safe.contains("TOKEN"));
            assertTrue(safe.startsWith("中文 dsh web:")); assertTrue(safe.contains("token=***"));
        }
    }
    @Test public void finalPartialLineIsRedactedAndLongLinesAreBounded() {
        DshAuthLog log = new DshAuthLog();
        assertEquals("", log.append("token=secret")); assertEquals("token=***\n", log.finish());
        assertEquals("", log.append("x".repeat(100_000)));
        assertEquals("[已略过超长输出]\n正常\n", log.append("\n正常\n"));
    }
}
