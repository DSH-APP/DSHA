package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GhManagedInstallTest {
  @Test
  public void environmentNotReadyWinsOverMissingFile() {
    assertEquals(
        GhManagedInstall.State.ENV_NOT_READY, GhManagedInstall.state(false, false, false, 0));
    assertEquals(
        GhManagedInstall.State.ENV_NOT_READY,
        GhManagedInstall.state(false, true, true, GhManagedInstall.BINARY_BYTES));
  }

  @Test
  public void classifiesPinnedBinary() {
    assertEquals(GhManagedInstall.State.MISSING, GhManagedInstall.state(true, false, false, 0));
    assertEquals(GhManagedInstall.State.NOT_REGULAR, GhManagedInstall.state(true, true, false, 0));
    assertEquals(
        GhManagedInstall.State.AVAILABLE,
        GhManagedInstall.state(true, true, true, GhManagedInstall.BINARY_BYTES));
    assertEquals(
        GhManagedInstall.State.SIZE_MISMATCH,
        GhManagedInstall.state(true, true, true, GhManagedInstall.BINARY_BYTES - 1));
  }

  @Test
  public void pinFactsAreSelfConsistent() {
    assertTrue(GhManagedInstall.VERSION.matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
    assertTrue(GhManagedInstall.BINARY_SHA256.matches("[0-9a-f]{64}"));
    assertTrue(GhManagedInstall.BINARY_BYTES > 0);
    assertEquals("github.com", GhManagedInstall.HOST);
    assertEquals("opt/gh/bin/gh", GhManagedInstall.BIN);
    assertEquals("root/.config/gh/hosts.yml", GhManagedInstall.HOSTS);
    assertTrue(GhManagedInstall.MAX_HOSTS_BYTES > 0);
  }
}
