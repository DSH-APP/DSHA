package com.deepseekharness.app.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 有界启动握手：允许 ROM 的普通输出，但只信任精确的受管标记。 */
public final class VirtualScreenBootstrap {
  private static final Pattern ERROR =
      Pattern.compile("(?m)^DSHA_VSCREEN_ERROR=([A-Za-z0-9_]{1,160})\\r?$");
  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  private int bytes;
  private String token = "";

  public String token() {
    return token;
  }

  public void accept(int value) throws IOException {
    if (++bytes > 65536) throw new IOException("CORE_BOOTSTRAP_LIMIT");
    if (value == '\n') {
      String text = line.toString(StandardCharsets.UTF_8.name()).replace("\r", "");
      line.reset();
      String failure = error(text);
      if (!failure.isEmpty()) throw new IOException(failure);
      String prefix = "DSHA_VSCREEN_BOOTSTRAP ";
      if (text.startsWith(prefix)) {
        String secret = text.substring(prefix.length());
        if (!secret.matches("[a-f0-9]{48}")) throw new IOException("CORE_BOOTSTRAP_INVALID");
        token = secret;
      }
    } else {
      if (line.size() >= 4096) throw new IOException("CORE_BOOTSTRAP_LIMIT");
      line.write(value);
    }
  }

  public static String error(String output) {
    if (output == null) return "";
    Matcher match = ERROR.matcher(output);
    return match.find() ? match.group(1) : "";
  }

  public static String failureCode(String stage, int sdk, Throwable failure) {
    String propagated = failure == null ? "" : error("DSHA_VSCREEN_ERROR=" + failure.getMessage());
    if (!propagated.isEmpty() && propagated.startsWith("CORE_")) return propagated;
    Throwable root = failure;
    java.util.Set<Throwable> seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (int i = 0; root != null && root.getCause() != null && i < 16 && seen.add(root); i++)
      root = root.getCause();
    String type =
        root == null ? "Unknown" : root.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "");
    String safeStage = stage != null && stage.matches("[A-Z_]{1,32}") ? stage : "START";
    return "CORE_" + safeStage + "_SDK" + Math.max(0, sdk) + "_" + type;
  }
}
