package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class PreviewPageSessionTest {
  private static final String URL = "http://127.0.0.1:3080/auth?token=fixture";

  @Test
  public void sameUrlNewGenerationRefreshesOnce() {
    PreviewPageSession page = new PreviewPageSession();
    var old = new PreviewPageSession.Identity(1, "first", URL);
    var replacement = new PreviewPageSession.Identity(3, "second", URL);
    assertTrue(page.claim(old));
    assertFalse(page.isCurrent(replacement));
    assertTrue(page.claim(replacement));
    assertFalse(page.claim(replacement));
    assertTrue(page.isCurrent(replacement));
  }

  @Test
  public void applicationRecreationCannotReuseCollidingGenerationAndUrl() {
    PreviewPageSession page = new PreviewPageSession();
    var old = new PreviewPageSession.Identity(1, "old-app-run", URL);
    var replacement = new PreviewPageSession.Identity(1, "new-app-run", URL);
    page.claim(old);
    assertFalse(page.isCurrent(replacement));
    assertTrue(page.claim(replacement));
  }

  @Test
  public void ordinaryReturnRotationAndLanguageChangeKeepPageIdentity() {
    PreviewPageSession page = new PreviewPageSession();
    var current = new PreviewPageSession.Identity(9, "run", URL);
    page.claim(current);
    for (int callback = 0; callback < 4; callback++) {
      var snapshot = new PreviewPageSession.Identity(9, "run", URL);
      assertTrue(page.isCurrent(snapshot));
      assertFalse(page.claim(snapshot));
    }
    assertEquals(current, page.identity());
  }

  @Test
  public void stoppedOrPendingRuntimeRejectsCallbacksWithoutClaimingReplacement() {
    PreviewPageSession page = new PreviewPageSession();
    var old = new PreviewPageSession.Identity(2, "old", URL);
    page.claim(old);
    assertFalse(page.isCurrent(null));
    assertFalse(page.claim(null));
    assertEquals(old, page.identity());
    page.clear();
    assertNull(page.identity());
    assertFalse(page.isCurrent(old));
  }

  @Test
  public void changedCredentialsAndPortInvalidateSavedHistory() {
    PreviewPageSession page = new PreviewPageSession();
    page.claim(new PreviewPageSession.Identity(4, "run", URL));
    assertFalse(
        page.isCurrent(
            new PreviewPageSession.Identity(4, "run", "http://127.0.0.1:3080/auth?token=new")));
    assertFalse(
        page.isCurrent(
            new PreviewPageSession.Identity(4, "run", "http://127.0.0.1:3982/auth?token=fixture")));
  }

  @Test(expected = IllegalArgumentException.class)
  public void recoveryLocalhostCannotBecomeFormalPageIdentity() {
    new PreviewPageSession.Identity(1, "run", "http://localhost:3982/auth?token=fixture");
  }

  @Test(expected = IllegalArgumentException.class)
  public void missingInstanceCannotAuthorizeHistoryFromAnotherAppLifetime() {
    new PreviewPageSession.Identity(1, "", URL);
  }
}
