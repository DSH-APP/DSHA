package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HostDeviceCommandGuardTest {
  @Test
  public void localDangerousCommandsStayBlockedEvenInRelaxedPaths() {
    assertFalse(HostDeviceCommandGuard.allowed("fastboot devices"));
    assertFalse(HostDeviceCommandGuard.allowed("reboot bootloader"));
    assertFalse(HostDeviceCommandGuard.allowed("dd if=x of=/dev/block/by-name/boot"));
    assertFalse(HostDeviceCommandGuard.allowed("adb nodaemon server"));
    assertTrue(HostDeviceCommandGuard.allowed("getprop ro.product.model"));
  }
}
