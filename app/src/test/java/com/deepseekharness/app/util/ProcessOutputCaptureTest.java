package com.deepseekharness.app.util;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ProcessOutputCaptureTest {
    private static void add(ProcessOutputCapture capture, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); capture.write(bytes, 0, bytes.length);
    }
    @Test public void failureAtRealEndSurvivesLargeOutput() {
        ProcessOutputCapture capture = new ProcessOutputCapture(8, 16);
        add(capture, "BOOT_OK\n");
        for (int i = 0; i < 10000; i++) add(capture, "progress\n");
        add(capture, "FATAL: exit 126\n");
        assertEquals("BOOT_OK\n", capture.head());
        assertTrue(capture.tail().endsWith("FATAL: exit 126\n"));
        assertTrue(capture.truncated()); assertEquals(90024, capture.totalBytes());
    }
    @Test public void wrapsAcrossChunksAndHandlesLargeSingleChunk() {
        ProcessOutputCapture capture = new ProcessOutputCapture(3, 5);
        add(capture, "123"); add(capture, "4567890"); add(capture, "ab");
        assertEquals("123", capture.head()); assertEquals("890ab", capture.tail());
        assertEquals(12, capture.totalBytes());
    }
    @Test public void zeroAndExactLimitsRemainBounded() {
        ProcessOutputCapture empty = new ProcessOutputCapture(0, 0); add(empty,"abc");
        assertEquals("", empty.head()); assertEquals("", empty.tail()); assertTrue(empty.truncated());
        ProcessOutputCapture exact = new ProcessOutputCapture(3, 3); add(exact,"abc");
        assertEquals("abc", exact.tail()); assertFalse(exact.truncated());
    }
    @Test public void tailDoesNotStartWithPartialUtf8Character() {
        ProcessOutputCapture capture = new ProcessOutputCapture(0, 5); add(capture,"你好");
        assertEquals("好",capture.tail());
    }
}
