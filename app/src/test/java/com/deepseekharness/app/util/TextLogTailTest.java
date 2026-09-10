package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class TextLogTailTest {
    private File log(String text) throws Exception {
        File file = File.createTempFile("dsha-log-test-", ".txt"); file.deleteOnExit();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }
    @Test public void smallAndEmptyLogsRemainReadable() throws Exception {
        assertEquals("", TextLogTail.read(log(""), 32));
        assertEquals("错误：启动失败\n", TextLogTail.read(log("错误：启动失败\n"), 64));
    }
    @Test public void largeFileKeepsOnlyLatestOutput() throws Exception {
        String tail = TextLogTail.read(log("earlier".repeat(100000) + "\nLAST_FAILURE\n"), 128);
        assertTrue(tail.startsWith("（较早输出已省略"));
        assertTrue(tail.endsWith("LAST_FAILURE\n"));
        assertTrue(tail.length() < 200);
    }
    @Test public void truncatedUtf8DoesNotCreateBrokenCharacters() throws Exception {
        String tail = TextLogTail.read(log("中文汉字\nEND"), 5);
        assertTrue(tail.endsWith("\nEND")); assertFalse(tail.contains("\uFFFD"));
    }
    @Test public void exportUsesExistingSecretRedaction() throws Exception {
        String report = SensitiveData.redact(TextLogTail.read(log("API_KEY=sk-12345678901234567890\nAuthorization: Bearer fake-token-value\nerror=timeout"), 256));
        assertFalse(report.contains("12345678901234567890")); assertFalse(report.contains("fake-token-value"));
        assertTrue(report.contains("error=timeout"));
    }
    @Test public void clippedFieldNeverExportsAnUnlabelledSecretFragment() throws Exception {
        String tail = TextLogTail.read(log("API_KEY=" + "privatevalue".repeat(30) + "\nerror=failed\n"), 70);
        assertFalse(tail.contains("privatevalue")); assertTrue(tail.endsWith("error=failed\n"));
    }
    @Test public void completeLineAtLimitIsPreserved() throws Exception {
        assertTrue(TextLogTail.read(log("old\nLATEST\n"), 7).endsWith("\nLATEST\n"));
    }
    @Test(expected=IllegalArgumentException.class) public void invalidLimitRejected() throws Exception { TextLogTail.read(log("x"), 0); }
}
