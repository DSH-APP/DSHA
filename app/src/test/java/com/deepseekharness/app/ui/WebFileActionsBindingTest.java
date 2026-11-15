package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.BrowserThemeGate;
import com.deepseekharness.app.util.PreviewPageSession;
import org.junit.Test;

/** The bridge's actual document identity predicate, without Android I/O. */
public final class WebFileActionsBindingTest {
  private final Object source = new Object();
  private final PreviewPageSession.Identity identity =
      new PreviewPageSession.Identity(192, "runtime-192", "http://127.0.0.1:41023/?token=fixture");

  @Test
  public void acceptsOnlyCurrentOwnerDocumentAndRuntime() {
    BrowserThemeGate page = new BrowserThemeGate(source, identity);
    assertTrue(WebFileActions.matchesBinding(page, source, page.nonce(), 192, "runtime-192"));
    assertFalse(WebFileActions.matchesBinding(null, source, page.nonce(), 192, "runtime-192"));
    assertFalse(
        WebFileActions.matchesBinding(page, new Object(), page.nonce(), 192, "runtime-192"));
    assertFalse(WebFileActions.matchesBinding(page, source, "old-document", 192, "runtime-192"));
    assertFalse(WebFileActions.matchesBinding(page, source, page.nonce(), 191, "runtime-192"));
    assertFalse(WebFileActions.matchesBinding(page, source, page.nonce(), 192, "old-runtime"));
  }

  @Test
  public void oldDocumentCannotEnqueueAfterSameOriginNavigationOrReplacement() {
    BrowserThemeGate first = new BrowserThemeGate(source, identity);
    BrowserThemeGate nextDocument = new BrowserThemeGate(source, identity);
    assertFalse(
        WebFileActions.matchesBinding(nextDocument, source, first.nonce(), 192, "runtime-192"));
    PreviewPageSession.Identity replacement =
        new PreviewPageSession.Identity(193, "runtime-193", identity.authUrl());
    BrowserThemeGate nextRuntime = new BrowserThemeGate(source, replacement);
    assertFalse(
        WebFileActions.matchesBinding(nextRuntime, source, first.nonce(), 192, "runtime-192"));
    assertTrue(
        WebFileActions.matchesBinding(
            nextRuntime, source, nextRuntime.nonce(), 193, "runtime-193"));
  }

  @Test
  public void destinationGuardIsLocalToTheExplicitLiveDocumentAndRetiresOnce() {
    java.util.concurrent.atomic.AtomicBoolean current =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    String token = WebFileActions.destinationToken(current::get);
    assertTrue(WebFileActions.destinationCurrent(token));
    assertFalse(WebFileActions.destinationCurrent("unrelated-or-lost-process-token"));
    current.set(false);
    assertFalse(WebFileActions.destinationCurrent(token));
    WebFileActions.retireDestination(token);
    current.set(true);
    assertFalse(WebFileActions.destinationCurrent(token));
  }
}
