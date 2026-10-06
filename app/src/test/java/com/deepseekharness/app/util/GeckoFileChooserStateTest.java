package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class GeckoFileChooserStateTest {
  @Test
  public void restartedPageCannotClaimAnOldSystemChooserResult() {
    GeckoFileChooserState<Object> chooser = new GeckoFileChooserState<>();
    BrowserUploadRequestState<Object, Object> uploads = new BrowserUploadRequestState<>();
    Object oldPage = new Object(), oldCache = new Object(), oldRequest = new Object();
    var oldTicket = uploads.begin(oldPage, oldCache);
    assertTrue(chooser.begin(oldRequest));

    uploads.invalidate(); // 新运行代次取消网页请求，但旧系统选择器仍在途。
    Object newPage = new Object(), newCache = new Object(), newRequest = new Object();
    assertFalse(chooser.begin(newRequest));
    assertSame(oldRequest, chooser.takeResult(oldRequest));
    assertFalse(uploads.owns(oldTicket, newPage, newCache));

    assertTrue(chooser.begin(newRequest));
    assertNull(chooser.takeResult(oldRequest));
    assertSame(newRequest, chooser.pending());
    assertSame(newRequest, chooser.takeResult(newRequest));
    assertNull(chooser.takeResult());
  }
}
