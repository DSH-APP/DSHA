package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public class RecoveryDescriptorLimitsTest {
  @Test
  public void proofRowsShareOneBudget() throws Exception {
    RecoveryDescriptorLimits.counts(6, 0);
    RecoveryDescriptorLimits.counts(RecoveryDescriptorLimits.MAX_ENTRIES, 0);
    assertThrows(IOException.class, () -> RecoveryDescriptorLimits.counts(5, 0));
    assertThrows(IOException.class, () -> RecoveryDescriptorLimits.counts(99999, 2));
    assertThrows(IOException.class, () -> RecoveryDescriptorLimits.counts(6, -1));
  }

  @Test
  public void pathsCountUtf8BytesAndComponentsAndNeverAllowTraversal() throws Exception {
    assertEquals("a".repeat(4096), RecoveryDescriptorLimits.path("a".repeat(4096)));
    assertThrows(IOException.class, () -> RecoveryDescriptorLimits.path("中".repeat(1366)));
    assertThrows(IOException.class, () -> RecoveryDescriptorLimits.path("a/".repeat(128) + "a"));
    for (String bad : new String[] {"../x", "/x", "a//b", "a/./b", "a\\b", "a\0b", ""})
      assertThrows(IOException.class, () -> RecoveryDescriptorLimits.path(bad));
  }
}
