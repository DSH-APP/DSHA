package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.File;
import org.junit.Assume;
import org.junit.Test;

public class BridgeProcessStatusTest {
  @Test
  public void onlyExactPathSelectsHostDiagnostic() {
    assertTrue(BridgeProcessStatus.isExactPath("/proc/self/status"));
    for (String path :
        new String[] {
          null,
          "/proc/version",
          "/proc/self",
          "/proc/self/status/child",
          "/proc/self/./status",
          "/proc/123/status",
          "/proc/self/status "
        }) assertFalse(String.valueOf(path), BridgeProcessStatus.isExactPath(path));
  }

  @Test
  public void readsZeroLengthProcStatusWithoutTruncatingToStatSize() throws Exception {
    File status = new File("/proc/self/status");
    Assume.assumeTrue("procfs unavailable", status.isFile());
    assertEquals(0, status.length());
    assertTrue(BridgeProcessStatus.read().contains("Name:"));
  }
}
