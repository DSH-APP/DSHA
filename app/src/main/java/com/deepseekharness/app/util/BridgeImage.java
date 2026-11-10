package com.deepseekharness.app.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded byte reading and magic-byte identification for explicit bridge image reads. */
public final class BridgeImage {
  public static final int MAX_BYTES = 8 * 1024 * 1024;
  public static final int MAX_PIXELS = 16 * 1024 * 1024;
  public static final int MAX_DIMENSION = 8192;

  private BridgeImage() {}

  public static byte[] read(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (output.size() + count > MAX_BYTES) throw new IOException("IMAGE_TOO_LARGE");
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  public static String mediaType(byte[] bytes) {
    if (bytes == null) return null;
    if (bytes.length >= 8
        && Arrays.equals(
            Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      return "image/png";
    if (bytes.length >= 3
        && (bytes[0] & 255) == 255
        && (bytes[1] & 255) == 216
        && (bytes[2] & 255) == 255) return "image/jpeg";
    if (bytes.length >= 6) {
      String header = new String(bytes, 0, 6, StandardCharsets.US_ASCII);
      if (header.equals("GIF87a") || header.equals("GIF89a")) return "image/gif";
    }
    if (bytes.length >= 12
        && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
        && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
    return null;
  }

  public static boolean safeDimensions(int width, int height) {
    return width > 0
        && height > 0
        && width <= MAX_DIMENSION
        && height <= MAX_DIMENSION
        && (long) width * height <= MAX_PIXELS;
  }

  /** Reject animated containers and malformed chunk boundaries before native decoding. */
  public static boolean isStatic(byte[] bytes, String media) {
    if (bytes == null || media == null || !media.equals(mediaType(bytes))) return false;
    if (media.equals("image/jpeg")) return true;
    // Even a one-frame GIF may contain unbounded decoder work in later frames.
    // Reject the format rather than guessing from the first decoded frame.
    if (media.equals("image/gif")) return false;
    if (media.equals("image/png")) {
      int offset = 8;
      boolean first = true;
      while (offset <= bytes.length - 12) {
        long size = unsigned32(bytes, offset, false);
        long end = (long) offset + 12 + size;
        if (end > bytes.length) return false;
        String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
        if (first && (!type.equals("IHDR") || size != 13)) return false;
        if (type.equals("acTL") || type.equals("fcTL") || type.equals("fdAT")) return false;
        if (type.equals("IEND")) return size == 0 && end == bytes.length;
        first = false;
        offset = (int) end;
      }
      return false;
    }
    if (media.equals("image/webp")) {
      if (unsigned32(bytes, 4, true) + 8 != bytes.length) return false;
      int offset = 12;
      while (offset <= bytes.length - 8) {
        String type = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
        long size = unsigned32(bytes, offset + 4, true);
        long end = (long) offset + 8 + size + (size & 1);
        if (end > bytes.length) return false;
        if (type.equals("ANIM") || type.equals("ANMF")) return false;
        if (type.equals("VP8X") && (size != 10 || (bytes[offset + 8] & 2) != 0)) return false;
        offset = (int) end;
      }
      return offset == bytes.length;
    }
    return false;
  }

  private static long unsigned32(byte[] data, int offset, boolean littleEndian) {
    long result = 0;
    for (int i = 0; i < 4; i++) {
      int index = littleEndian ? offset + 3 - i : offset + i;
      result = (result << 8) | (data[index] & 255);
    }
    return result;
  }

  /** Both values are produced internally: a known MIME type and NO_WRAP base64. */
  public static String envelope(String media, String base64) {
    return "{\"kind\":\"dsha-file-image-v1\",\"mimeType\":\""
        + media
        + "\",\"data\":\""
        + base64
        + "\"}";
  }
}
