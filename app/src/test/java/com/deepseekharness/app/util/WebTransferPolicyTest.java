package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebTransferPolicyTest {
  @Test
  public void uploadCapacityIncludesFiveDecimalGigabytesAndUsesCurrentSpace() {
    assertEquals(5L * 1024 * 1024 * 1024, WebTransferPolicy.UPLOAD_LIMIT);
    WebTransferPolicy.checkSize(5_000_000_000L, -1, WebTransferPolicy.UPLOAD_LIMIT, true);
    WebTransferPolicy.checkSize(
        WebTransferPolicy.UPLOAD_LIMIT, -1, WebTransferPolicy.UPLOAD_LIMIT, true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WebTransferPolicy.checkSize(
                WebTransferPolicy.UPLOAD_LIMIT + 1, -1, WebTransferPolicy.UPLOAD_LIMIT, true));
    long reserve = WebTransferPolicy.UPLOAD_RESERVE;
    WebTransferPolicy.checkUploadSpace(0, reserve);
    WebTransferPolicy.checkUploadSpace(65536, reserve + 65536);
    WebTransferPolicy.checkUploadSpace(Long.MAX_VALUE - reserve, Long.MAX_VALUE);
    for (long[] values :
        new long[][] {
          {1, reserve}, {0, 0}, {0, -1}, {-1, Long.MAX_VALUE}, {Long.MAX_VALUE, Long.MAX_VALUE}
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> WebTransferPolicy.checkUploadSpace(values[0], values[1]));
    // 同一写入者开始时有空间不代表随后其他进程占盘后仍能继续。
    WebTransferPolicy.checkUploadSpace(65536, reserve + 65536);
    assertThrows(
        IllegalArgumentException.class,
        () -> WebTransferPolicy.checkUploadSpace(65536, reserve + 65535));
    assertEquals(5_000_000_000L, WebTransferPolicy.DOWNLOAD_LIMIT);
  }

  @Test
  public void shareMimeRejectsHeaderSyntaxAndKeepsActualType() {
    assertEquals("text/markdown", WebTransferPolicy.mimeType("text/markdown"));
    assertEquals(
        "application/vnd.example+json", WebTransferPolicy.mimeType("application/vnd.example+json"));
    for (String bad :
        new String[] {null, "", "text/plain\r\nextra: value", "file:///secret", "text/*"})
      assertEquals("application/octet-stream", WebTransferPolicy.mimeType(bad));
  }

  @Test
  public void filenamesStayInsideOwnedDirectory() {
    assertEquals(".._.._secret", WebTransferPolicy.fileName("../../secret"));
    assertEquals("download.bin", WebTransferPolicy.fileName(".."));
    assertEquals("日志.zip", WebTransferPolicy.fileName("日志.zip"));
    assertTrue(WebTransferPolicy.fileName("x".repeat(300) + ".zip").endsWith(".zip"));
    String chinese = WebTransferPolicy.fileName("对话".repeat(120) + ".zip");
    assertTrue(chinese.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 240);
    assertTrue(chinese.endsWith(".zip"));
  }

  @Test
  public void incompleteAndOversizeAreNeverSuccessful() {
    WebTransferPolicy.checkSize(10, -1, 20, true);
    WebTransferPolicy.checkSize(10, 10, 20, true);
    for (long[] pair : new long[][] {{9, 10}, {11, 10}, {21, -1}, {0, 21}}) {
      try {
        WebTransferPolicy.checkSize(pair[0], pair[1], 20, true);
        fail();
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void downloadLimitUsesDecimalFiveGigabytesAtExactBoundary() {
    WebTransferPolicy.checkSize(
        5_000_000_000L, 5_000_000_000L, WebTransferPolicy.DOWNLOAD_LIMIT, true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WebTransferPolicy.checkSize(
                5_000_000_001L, -1, WebTransferPolicy.DOWNLOAD_LIMIT, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WebTransferPolicy.checkSize(
                0, 5_000_000_001L, WebTransferPolicy.DOWNLOAD_LIMIT, false));
  }

  @Test
  public void downloadsCannotReadAnotherServiceOrLocalPath() {
    String base = "http://127.0.0.1:3080/";
    assertTrue(WebPreviewPolicy.pageDownload(base, base + "file.zip"));
    assertTrue(WebPreviewPolicy.pageDownload(base, "blob:" + base + "id"));
    assertTrue(WebPreviewPolicy.pageDownload(base, "data:text/plain;base64,YQ=="));
    for (String bad :
        new String[] {
          "file:///private",
          "content://private/key",
          "blob:http://example.com/id",
          "blob:http://127.0.0.1:3090/id",
          "http://127.0.0.1:3090/"
        }) assertFalse(WebPreviewPolicy.pageDownload(base, bad));
  }
}
