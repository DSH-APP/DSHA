package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import org.junit.Test;

public class BridgeImageTest {
  @Test
  public void recognizesOnlySupportedImageSignatures() {
    byte[] png =
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jCXYAAAAASUVORK5CYII=");
    assertEquals("image/png", BridgeImage.mediaType(png));
    assertEquals(
        "image/jpeg", BridgeImage.mediaType(new byte[] {(byte) 255, (byte) 216, (byte) 255}));
    assertEquals("image/gif", BridgeImage.mediaType("GIF89a".getBytes(StandardCharsets.US_ASCII)));
    assertEquals(
        "image/webp", BridgeImage.mediaType("RIFF1234WEBP".getBytes(StandardCharsets.US_ASCII)));
    assertNull(BridgeImage.mediaType("<svg/>".getBytes(StandardCharsets.US_ASCII)));
    assertNull(BridgeImage.mediaType(new byte[] {(byte) 137, 80, 78}));
    assertNull(BridgeImage.mediaType(null));
  }

  @Test
  public void capsStreamingBytesEvenIfFileMetadataWasSmaller() throws Exception {
    byte[] allowed = new byte[BridgeImage.MAX_BYTES];
    assertArrayEquals(allowed, BridgeImage.read(new ByteArrayInputStream(allowed)));
    try {
      BridgeImage.read(new ByteArrayInputStream(new byte[BridgeImage.MAX_BYTES + 1]));
      fail("over-limit stream accepted");
    } catch (IOException expected) {
      assertEquals("IMAGE_TOO_LARGE", expected.getMessage());
    }
  }

  @Test
  public void dimensionsArePositiveBoundedAndOverflowSafe() {
    assertTrue(BridgeImage.safeDimensions(4096, 4096));
    assertFalse(BridgeImage.safeDimensions(0, 1));
    assertFalse(BridgeImage.safeDimensions(8193, 1));
    assertFalse(BridgeImage.safeDimensions(8192, 8192));
    assertFalse(BridgeImage.safeDimensions(Integer.MAX_VALUE, Integer.MAX_VALUE));
  }

  @Test
  public void rejectsAnimationsAndMalformedContainerBoundaries() {
    byte[] png =
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jCXYAAAAASUVORK5CYII=");
    assertTrue(BridgeImage.isStatic(png, "image/png"));
    assertFalse(BridgeImage.isStatic(Arrays.copyOf(png, png.length - 1), "image/png"));
    byte[] animated = new byte[png.length + 20];
    System.arraycopy(png, 0, animated, 0, 33);
    byte[] actl = new byte[] {0, 0, 0, 8, 'a', 'c', 'T', 'L', 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0};
    System.arraycopy(actl, 0, animated, 33, actl.length);
    System.arraycopy(png, 33, animated, 33 + actl.length, png.length - 33);
    assertFalse(BridgeImage.isStatic(animated, "image/png"));
    assertFalse(BridgeImage.isStatic("GIF89a".getBytes(StandardCharsets.US_ASCII), "image/gif"));
    byte[] webp = new byte[30];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
    webp[4] = 22;
    System.arraycopy("WEBPVP8X".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 8);
    webp[16] = 10;
    assertTrue(BridgeImage.isStatic(webp, "image/webp"));
    webp[20] = 2;
    assertFalse(BridgeImage.isStatic(webp, "image/webp"));
    webp[20] = 0;
    System.arraycopy("ANIM".getBytes(StandardCharsets.US_ASCII), 0, webp, 12, 4);
    assertFalse(BridgeImage.isStatic(webp, "image/webp"));
    webp[4] = 23;
    assertFalse(BridgeImage.isStatic(webp, "image/webp"));
  }

  @Test
  public void envelopeDoesNotEscapeBase64Slashes() {
    String base64 = "/////w==";
    String envelope = BridgeImage.envelope("image/png", base64);
    assertEquals(
        "{\"kind\":\"dsha-file-image-v1\",\"mimeType\":\"image/png\",\"data\":\"/////w==\"}",
        envelope);
    assertFalse(envelope.contains("\\/"));
  }

  @Test
  public void maximumSlashDensePayloadFitsTheDoubleWrappedHttpLimit() {
    byte[] data = new byte[BridgeImage.MAX_BYTES];
    Arrays.fill(data, (byte) 255);
    String base64 = Base64.getEncoder().encodeToString(data);
    String envelope = BridgeImage.envelope("image/png", base64);
    // HttpShellService's outer JSON escaping escapes quotes and backslashes, not '/'.
    String wire = "{\"result\":\"" + envelope.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    assertTrue(base64.startsWith("////////////////"));
    assertEquals(11184812, base64.length());
    assertEquals(11184899, wire.getBytes(StandardCharsets.UTF_8).length);
    assertTrue(wire.length() < 12 * 1024 * 1024);
  }
}
