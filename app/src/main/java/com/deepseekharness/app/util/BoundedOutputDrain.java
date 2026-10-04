package com.deepseekharness.app.util;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Nonblocking per-pass pipe drain with a bounded, redacted diagnostic tail. */
public final class BoundedOutputDrain {
  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  private final StringBuilder tail = new StringBuilder();
  private boolean oversized;

  public int drain(InputStream input) throws IOException {
    byte[] bytes = new byte[8192];
    int remaining = 256 * 1024, total = 0;
    while (remaining > 0) {
      int available = input.available();
      if (available <= 0) break;
      int count = input.read(bytes, 0, Math.min(bytes.length, Math.min(available, remaining)));
      if (count <= 0) break;
      total += count;
      remaining -= count;
      for (int i = 0; i < count; i++) {
        if (bytes[i] == '\n') {
          finishLine();
        } else if (!oversized) {
          if (line.size() >= 65536) {
            oversized = true;
            line.reset();
          } else line.write(bytes[i]);
        }
      }
    }
    return total;
  }

  private void finishLine() {
    tail.append(
            oversized
                ? "[OVERSIZED_PROCESS_LINE_OMITTED]"
                : SensitiveData.redact(new String(line.toByteArray(), StandardCharsets.UTF_8)))
        .append('\n');
    line.reset();
    oversized = false;
    if (tail.length() > 32768) tail.delete(0, tail.length() - 16384);
  }

  public String diagnostics() {
    // Redact whole bounded lines before tail trimming; a secret split across read chunks cannot
    // leak.
    if (oversized || line.size() > 0) finishLine();
    return tail.toString();
  }
}
