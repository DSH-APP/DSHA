package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VirtualScreenRequestFenceTest {
  @Test
  public void everyConnectionAndGenerationComponentMustStillMatch() {
    VirtualScreenRequestFence fence = new VirtualScreenRequestFence(7, 8801, "owned", "display", 9);
    assertTrue(fence.matches(7, 7, 8801, "owned", "display", 9));
    assertFalse(fence.matches(8, 7, 8801, "owned", "display", 9));
    assertFalse(fence.matches(7, -1, 8801, "owned", "display", 9));
    assertFalse(fence.matches(7, 7, 8802, "owned", "display", 9));
    assertFalse(fence.matches(7, 7, 8801, "new-owner", "display", 9));
    assertFalse(fence.matches(7, 7, 8801, "owned", "new-display", 9));
    assertFalse(fence.matches(7, 7, 8801, "owned", "display", 10));
  }
}
