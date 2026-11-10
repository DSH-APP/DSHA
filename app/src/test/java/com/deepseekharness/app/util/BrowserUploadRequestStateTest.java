package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserUploadRequestStateTest {
  @Test
  public void browserAdapterRejectsOldChooserAndNavigationResults() {
    BrowserUploadRequestState<Object, Object> requests = new BrowserUploadRequestState<>();
    Object browser = new Object(), cache = new Object();
    var first = requests.begin(browser, cache);
    assertTrue(requests.owns(first, browser, cache));
    var replacement = requests.begin(browser, cache);
    assertFalse(requests.owns(first, browser, cache));
    assertTrue(requests.owns(replacement, browser, cache));
    requests.finish(first);
    assertTrue(requests.owns(replacement, browser, cache));
    requests.invalidate(); // navigation or cancelled chooser
    assertFalse(requests.owns(replacement, browser, cache));
    var next = requests.begin(browser, cache);
    assertFalse(requests.owns(next, new Object(), cache));
    assertFalse(requests.owns(next, browser, new Object()));
    requests.finish(next);
    assertFalse(requests.owns(next, browser, cache));
  }

  @Test
  public void retainedPageCanReattachButClosedPageCannotAcceptLateCopy() {
    BrowserUploadRequestState<Object, Object> requests = new BrowserUploadRequestState<>();
    Object browser = new Object(), cache = new Object();
    var request = requests.begin(browser, cache);
    // Rotation leaves the same retained browser and cache owned by the new Activity.
    assertTrue(requests.owns(request, browser, cache));
    requests.close();
    assertFalse(requests.owns(request, browser, cache));
    assertThrows(IllegalStateException.class, () -> requests.begin(browser, cache));
  }
}
