package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import org.junit.Test;

public final class RootShellCandidatesTest {
  @Test
  public void existingSystemSuWinsWithoutInspectingLaterCandidates() {
    var inspected = new ArrayList<String>();
    assertEquals(
        "/system/xbin/su",
        RootShellCandidates.firstVerified(
            path -> {
              inspected.add(path);
              return path.equals("/system/xbin/su");
            }));
    assertEquals(java.util.List.of("/system/bin/su", "/system/xbin/su"), inspected);
  }

  @Test
  public void kernelSuAndAPatchAreOrderedTrustedCandidates() {
    assertEquals(
        "/data/adb/ksu/bin/su",
        RootShellCandidates.firstVerified(
            path -> path.equals("/data/adb/ksu/bin/su") || path.equals("/data/adb/ap/bin/su")));
    assertEquals(
        "/data/adb/ap/bin/su",
        RootShellCandidates.firstVerified(path -> path.equals("/data/adb/ap/bin/su")));
  }

  @Test
  public void unverifiedCandidatesAreNeverSelectedAndDiscoveryCannotReadAppPath() {
    var inspected = new ArrayList<String>();
    assertNull(
        RootShellCandidates.firstVerified(
            path -> {
              inspected.add(path);
              return false;
            }));
    assertEquals(9, inspected.size());
    assertTrue(inspected.stream().allMatch(path -> path.startsWith("/")));
    assertFalse(inspected.contains("su"));
    assertFalse(
        inspected.stream().anyMatch(path -> path.contains("/files/") || path.contains("/tmp/")));
  }
}
