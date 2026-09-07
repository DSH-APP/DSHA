package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class WebTransferPolicyTest {
    @Test public void filenamesStayInsideOwnedDirectory() {
        assertEquals(".._.._secret", WebTransferPolicy.fileName("../../secret"));
        assertEquals("download.bin", WebTransferPolicy.fileName(".."));
        assertEquals("日志.zip", WebTransferPolicy.fileName("日志.zip"));
        assertTrue(WebTransferPolicy.fileName("x".repeat(300)+".zip").endsWith(".zip"));
        String chinese=WebTransferPolicy.fileName("对话".repeat(120)+".zip");
        assertTrue(chinese.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=240);
        assertTrue(chinese.endsWith(".zip"));
    }
    @Test public void incompleteAndOversizeAreNeverSuccessful() {
        WebTransferPolicy.checkSize(10, -1, 20, true);
        WebTransferPolicy.checkSize(10, 10, 20, true);
        for (long[] pair : new long[][]{{9,10},{11,10},{21,-1},{0,21}}) {
            try { WebTransferPolicy.checkSize(pair[0],pair[1],20,true); fail(); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void downloadsCannotReadAnotherServiceOrLocalPath() {
        String base="http://127.0.0.1:3080/";
        assertTrue(WebPreviewPolicy.pageDownload(base,base+"file.zip"));
        assertTrue(WebPreviewPolicy.pageDownload(base,"blob:"+base+"id"));
        assertTrue(WebPreviewPolicy.pageDownload(base,"data:text/plain;base64,YQ=="));
        for(String bad:new String[]{"file:///private","content://private/key","blob:http://example.com/id","blob:http://127.0.0.1:3090/id", "http://127.0.0.1:3090/"})
            assertFalse(WebPreviewPolicy.pageDownload(base,bad));
    }
}
