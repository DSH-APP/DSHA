package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.core.WebLifecycleController.WebRun;
import com.deepseekharness.app.util.PreviewPageSession;
import com.deepseekharness.app.util.WebLifecycle;
import org.junit.Test;

public class WebPageIdentityTest {
  private static final String URL = "http://127.0.0.1:3080/?dsh-session=fixed";

  private static WebRun ready(WebLifecycle gate) {
    WebRun run = new WebRun(gate.beginStart(false, false));
    run.launcher = new WebProcessSessionTest.Child();
    assertTrue(WebLifecycleController.publishAuthenticatedRun(gate, run, URL));
    gate.finishStart(run.generation);
    return run;
  }

  @Test
  public void sameUrlAndResetGenerationCannotReuseAnotherApplicationInstancePage() {
    WebLifecycle first = new WebLifecycle();
    WebLifecycle second = new WebLifecycle();
    var old = WebLifecycleController.readyWebPageIdentity(first, ready(first), false);
    var current = WebLifecycleController.readyWebPageIdentity(second, ready(second), false);
    assertEquals(old.authUrl(), current.authUrl());
    assertEquals(old.generation(), current.generation());
    assertNotEquals(old.instanceId(), current.instanceId());
    PreviewPageSession page = new PreviewPageSession();
    assertTrue(page.claim(old));
    assertTrue(page.claim(current));
    assertFalse(page.claim(current));
  }

  @Test
  public void restartingRevokesOldPageBeforeNewAuthenticationCompletes() {
    WebLifecycle gate = new WebLifecycle();
    WebRun old = ready(gate);
    assertNotNull(WebLifecycleController.readyWebPageIdentity(gate, old, false));
    gate.beginStop();
    assertNull(WebLifecycleController.readyWebPageIdentity(gate, old, false));
    gate.finishStop(gate.generation());
    WebRun next = new WebRun(gate.beginStart(false, false));
    next.launcher = new WebProcessSessionTest.Child();
    assertNull(WebLifecycleController.readyWebPageIdentity(gate, next, false));
    assertTrue(WebLifecycleController.publishAuthenticatedRun(gate, next, URL));
    assertNull(WebLifecycleController.readyWebPageIdentity(gate, next, false));
    gate.finishStart(next.generation);
    assertNotNull(WebLifecycleController.readyWebPageIdentity(gate, next, false));
    assertNull(WebLifecycleController.readyWebPageIdentity(gate, old, false));
  }

  @Test
  public void exitedOrFailedRunCannotAuthorizeExistingDomCallbacks() {
    WebLifecycle gate = new WebLifecycle();
    WebRun run = ready(gate);
    PreviewPageSession page = new PreviewPageSession();
    assertTrue(page.claim(WebLifecycleController.readyWebPageIdentity(gate, run, false)));
    assertFalse(page.isCurrent(WebLifecycleController.readyWebPageIdentity(gate, run, true)));
    run.launcher.destroy();
    assertNull(WebLifecycleController.readyWebPageIdentity(gate, run, false));
    assertFalse(page.isCurrent(WebLifecycleController.readyWebPageIdentity(gate, run, false)));
  }
}
