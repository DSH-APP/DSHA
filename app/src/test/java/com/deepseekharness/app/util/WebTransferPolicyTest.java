package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebTransferPolicyTest {
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
  public void uploadCopyIsBoundedByDiskNotByQuarterGigabyte() {
    // 单次挑选的原文天花板必须容得下用户要的 5 GiB 单文件（旧值 256 MiB 是用户撞到的那条）。
    assertEquals(5L * 1024 * 1024 * 1024, WebTransferPolicy.UPLOAD_LIMIT);
    assertTrue(WebTransferPolicy.UPLOAD_LIMIT > 5L * 1000 * 1000 * 1000);
    // 保留余量必须明显小于上限，否则小文件也会被误拒。
    assertTrue(WebTransferPolicy.UPLOAD_RESERVE < WebTransferPolicy.UPLOAD_LIMIT / 2);
    // 下载方向不受本次调整影响。
    assertEquals(2L * 1024 * 1024 * 1024, WebTransferPolicy.DOWNLOAD_LIMIT);
  }

  @Test
  public void uploadSpaceGuardKeepsAReserveAndFailsClosed() {
    long reserve = WebTransferPolicy.UPLOAD_RESERVE;
    // 正好写满「可用空间减保留量」仍算通过；再多一个字节就拒绝。
    WebTransferPolicy.checkUploadSpace(0, reserve);
    WebTransferPolicy.checkUploadSpace(4L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024 + reserve);
    for (long[] pair :
        new long[][] {
          {4L * 1024 * 1024 * 1024 + 1, 4L * 1024 * 1024 * 1024 + reserve},
          {1, reserve},
          {-1, reserve * 4},
          {0, 0},
          {0, -1}
        }) {
      try {
        WebTransferPolicy.checkUploadSpace(pair[0], pair[1]);
        fail("expected rejection for copied=" + pair[0] + " usable=" + pair[1]);
      } catch (IllegalArgumentException expected) {
      }
    }
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
