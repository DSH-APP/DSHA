package com.deepseekharness.app.runtime;

import org.junit.Test;

import static org.junit.Assert.*;

/** Isolated policy fixture: hidden foreign processes must not impersonate app Web. */
public final class WebProcessScanPolicyTest {
  @Test
  public void unknownDeniedPidIsNotAssumedToBeAppWeb() {
    assertFalse(
        WebProcessManager.globalScanUnconfirmed(WebProcessManager.Kind.DENIED, false, null, 10042));
    assertFalse(
        WebProcessManager.globalScanUnconfirmed(WebProcessManager.Kind.OTHER, false, null, 10042));
    assertFalse(
        WebProcessManager.globalScanUnconfirmed(WebProcessManager.Kind.DENIED, true, 10123, 10042));
  }

  @Test
  public void knownAppUidDenialAndReadableUnknownWebRemainFailClosed() {
    assertTrue(
        WebProcessManager.globalScanUnconfirmed(
            WebProcessManager.Kind.DENIED, false, 10042, 10042));
    assertTrue(
        WebProcessManager.globalScanUnconfirmed(WebProcessManager.Kind.WEB, false, null, 10042));
    assertFalse(
        WebProcessManager.globalScanUnconfirmed(WebProcessManager.Kind.OTHER, false, 10042, 10042));
  }
}
