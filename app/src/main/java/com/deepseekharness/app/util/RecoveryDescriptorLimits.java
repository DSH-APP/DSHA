package com.deepseekharness.app.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Schema 1 budgets. The producer and APK gate read these exact integer constants. */
public final class RecoveryDescriptorLimits {
  public static final int MAX_BYTES = 8388608;
  public static final int MAX_ENTRIES = 100000;
  public static final int MAX_PATH_BYTES = 4096;
  public static final int MAX_PATH_COMPONENTS = 128;

  private RecoveryDescriptorLimits() {}

  public static void counts(int files, int links) throws IOException {
    if (files < 6 || links < 0 || files > MAX_ENTRIES || links > MAX_ENTRIES - files)
      throw new IOException("RECOVERY_DESCRIPTOR_ENTRY_LIMIT");
  }

  public static String path(String value) throws IOException {
    if (value == null
        || value.isEmpty()
        || value.startsWith("/")
        || value.contains("\\")
        || value.contains("\0")
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES)
      throw new IOException("RECOVERY_PATH_INVALID");
    String[] parts = value.split("/", -1);
    if (parts.length > MAX_PATH_COMPONENTS) throw new IOException("RECOVERY_PATH_LIMIT");
    for (String part : parts)
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new IOException("RECOVERY_PATH_INVALID");
    return value;
  }
}
