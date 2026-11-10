package com.deepseekharness.app.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Header classification used for display and conservative image/partition warnings. */
public final class ExternalImageMetadata {
  public final long size;
  public final String sha256;
  public final String type;
  public final boolean sparse;
  public final boolean avb;

  public ExternalImageMetadata(long size, String sha256, String type, boolean sparse, boolean avb) {
    this.size = size;
    this.sha256 = sha256;
    this.type = type;
    this.sparse = sparse;
    this.avb = avb;
  }

  public static String classify(byte[] head, byte[] tail) {
    if (head != null
        && head.length >= 8
        && new String(head, 0, 8, StandardCharsets.US_ASCII).equals("ANDROID!"))
      return "android-boot";
    if (head != null
        && head.length >= 4
        && (head[0] & 0xff) == 0x3a
        && (head[1] & 0xff) == 0xff
        && (head[2] & 0xff) == 0x26
        && (head[3] & 0xff) == 0xed) return "sparse";
    if (head != null
        && head.length >= 4
        && new String(head, 0, 4, StandardCharsets.US_ASCII).equals("AVB0")) return "avb";
    if (tail != null && tail.length >= 64) {
      for (int i = 0; i <= tail.length - 4; i++)
        if (new String(tail, i, 4, StandardCharsets.US_ASCII).equals("AVB0")) return "avb-footer";
    }
    return "raw-or-unknown";
  }

  public static boolean isAvb(String type) {
    return type != null && type.toLowerCase(Locale.ROOT).startsWith("avb");
  }
}
