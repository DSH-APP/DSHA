package com.deepseekharness.app.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class WebPreviewPolicyTest {
  @Test
  public void preservesCustomPortAndDropsAuthQuery() {
    assertEquals(
        "http://127.0.0.1:8088/",
        WebPreviewPolicy.loopbackBaseUrl("http://127.0.0.1:8088/?token=secret"));
    assertTrue(
        WebPreviewPolicy.sameService("http://127.0.0.1:8088/", "http://127.0.0.1:8088/chat/123"));
    assertFalse(
        WebPreviewPolicy.sameService("http://127.0.0.1:8088/", "http://127.0.0.1:3090/exec"));
  }

  @Test
  public void rejectsForeignOriginsAndInvalidPorts() {
    for (String url :
        new String[] {
          null,
          "",
          "file:///etc/passwd",
          "https://example.org/",
          "http://127.0.0.1.evil:3080/",
          "http://token@127.0.0.1:3080/",
          "http://127.0.0.1:0/",
          "http://127.0.0.1:65536/"
        }) {
      assertNull(WebPreviewPolicy.loopbackBaseUrl(url));
    }
  }

  @Test
  public void desktopModeKeepsActualBrowserVersion() {
    String ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36";
    String desktop = WebPreviewPolicy.desktopUserAgent(ua);
    assertTrue(desktop.contains("Chrome/125.0.6422.165"));
    assertFalse(desktop.contains("Android"));
    assertFalse(desktop.contains(" Mobile "));
    assertEquals("", WebPreviewPolicy.desktopUserAgent(null));
  }
}
