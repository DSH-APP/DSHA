package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ApkGenerationTest {
  @Test
  public void markerBindsExactVersionCodeInStableAscii() {
    String marker = ApkGeneration.marker(328);
    assertEquals("DSHA_APK_GENERATION_V1\n328\n", marker);
    assertArrayEquals(marker.getBytes(StandardCharsets.US_ASCII), ApkGeneration.bytes(328));
    assertNotEquals(marker, ApkGeneration.marker(329));
  }

  @Test
  public void rejectsNonPositiveVersionCodes() {
    for (int value : new int[] {0, -1, Integer.MIN_VALUE}) {
      assertFalse(ApkGeneration.valid(value));
      assertThrows(IllegalArgumentException.class, () -> ApkGeneration.marker(value));
    }
  }
}
