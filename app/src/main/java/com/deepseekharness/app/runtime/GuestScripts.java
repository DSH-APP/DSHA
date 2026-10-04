package com.deepseekharness.app.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded signed scripts travel through stdin, never a giant bash -c/base64 argument. */
final class GuestScripts {
  static final int LIMIT = 2 * 1024 * 1024;
  static final String INSTALL =
      "set -eu; umask 077; DSHA_SCRIPT=$(mktemp /tmp/dsha-tool-script.XXXXXX); "
          + "trap 'rm -f -- \"$DSHA_SCRIPT\"' EXIT; tr -d '\\r' > \"$DSHA_SCRIPT\"; /bin/bash \"$DSHA_SCRIPT\"";

  static byte[] read(InputStream source) throws IOException {
    try (source;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = source.read(buffer)) != -1) {
        if (bytes.size() + count > LIMIT) throw new IOException("GUEST_SCRIPT_SIZE_LIMIT");
        bytes.write(buffer, 0, count);
      }
      return new String(bytes.toByteArray(), StandardCharsets.UTF_8)
          .replace("\r\n", "\n")
          .replace("\r", "\n")
          .getBytes(StandardCharsets.UTF_8);
    }
  }

  static void send(Process process, byte[] bytes) throws IOException {
    if (bytes.length > LIMIT) throw new IOException("GUEST_SCRIPT_SIZE_LIMIT");
    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(bytes);
      stdin.flush();
    }
  }

  private GuestScripts() {}
}
