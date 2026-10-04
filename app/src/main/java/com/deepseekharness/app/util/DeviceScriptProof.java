package com.deepseekharness.app.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 提取签名包装脚本的精确正文，注释相同不能证明实际字节。 */
public final class DeviceScriptProof {
  private static final String ANCHOR = "cat > /root/dsh-bin/adb-shell <<'EOF'\n";

  public static byte[] wrapper(byte[] setup) throws IOException {
    if (setup == null || setup.length > 2 * 1024 * 1024)
      throw new IOException("ADB_WRAPPER_SOURCE_LIMIT");
    String source =
        new String(setup, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
    int start = source.indexOf(ANCHOR), end = source.indexOf("\nEOF\n", start + ANCHOR.length());
    if (start < 0 || source.indexOf(ANCHOR, start + ANCHOR.length()) >= 0 || end < 0)
      throw new IOException("ADB_WRAPPER_SOURCE_ANCHOR");
    String body = source.substring(start + ANCHOR.length(), end + 1);
    if (!body.startsWith("#!/bin/bash\n") || body.length() > 64 * 1024)
      throw new IOException("ADB_WRAPPER_SOURCE_FORMAT");
    return body.getBytes(StandardCharsets.UTF_8);
  }

  private DeviceScriptProof() {}
}
