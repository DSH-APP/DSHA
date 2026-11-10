package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class ExternalImageMetadataTest {
  @Test
  public void recognizesBootAndAvbHeaders() {
    assertEquals(
        "android-boot",
        ExternalImageMetadata.classify(
            "ANDROID!".getBytes(StandardCharsets.US_ASCII), new byte[0]));
    assertEquals(
        "avb",
        ExternalImageMetadata.classify("AVB0".getBytes(StandardCharsets.US_ASCII), new byte[0]));
  }
}
