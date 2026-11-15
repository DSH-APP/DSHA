package com.deepseekharness.app.util;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Fixed diagnostic exception: only the bridge process's own proc status is readable. */
public final class BridgeProcessStatus {
  private static final String PATH = "/proc/self/status";
  private static final int LIMIT = 256 * 1024;

  private BridgeProcessStatus() {}

  public static boolean isExactPath(String path) {
    return PATH.equals(path);
  }

  public static String read() throws IOException {
    try (FileInputStream input = new FileInputStream(PATH);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (output.size() + count > LIMIT) return "TOO_LARGE: 内容超过 256 KiB";
        output.write(buffer, 0, count);
      }
      return output.toString(StandardCharsets.UTF_8.name());
    }
  }
}
