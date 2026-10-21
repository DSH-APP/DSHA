package com.deepseekharness.app.util;

import java.security.MessageDigest;

/** Exact ARGB hash of the fixed observed region; no tolerance, sampling or image substitution. */
public final class ScreenPixelFingerprint {
  private ScreenPixelFingerprint() {}

  @FunctionalInterface
  public interface Rows {
    void read(int[] pixels, int left, int y, int width);
  }

  public static String hash(int width, int height, ScreenWindowRegion.Bounds region, Rows rows)
      throws java.security.NoSuchAlgorithmException {
    if (region == null || !region.within(width, height))
      throw new IllegalArgumentException("SCREEN_WINDOW_GEOMETRY_UNAVAILABLE");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    int length = region.right() - region.left();
    int[] pixels = new int[length];
    byte[] bytes = new byte[length * 4];
    for (int y = region.top(); y < region.bottom(); y++) {
      rows.read(pixels, region.left(), y, length);
      for (int x = 0; x < length; x++) {
        bytes[x * 4] = (byte) (pixels[x] >>> 24);
        bytes[x * 4 + 1] = (byte) (pixels[x] >>> 16);
        bytes[x * 4 + 2] = (byte) (pixels[x] >>> 8);
        bytes[x * 4 + 3] = (byte) pixels[x];
      }
      digest.update(bytes);
    }
    StringBuilder hash = new StringBuilder(64);
    for (byte b : digest.digest())
      hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return hash.toString();
  }
}
