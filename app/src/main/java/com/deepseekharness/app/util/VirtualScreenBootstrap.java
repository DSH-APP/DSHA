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
      Matcher marker = ERROR.matcher(text);
      String failure = marker.find() ? marker.group(1) : "";
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
    return match.find() ? match.group(1) : startFailureCode(output);
  }

  /** Return only fixed diagnostic codes; never copy launch output or credentials into logs. */
  static String startFailureCode(String output) {
    if (output == null) return "";
    for (String stage : new String[] {"START", "COMMIT"})
      for (String reason : new String[] {
        "AUTH_REJECTED", "TICKET_REJECTED", "PATH_UNVERIFIED", "CHANNEL_UNAVAILABLE",
        "PLAN_REJECTED", "CREDENTIAL_UNAVAILABLE", "HTTP_ERROR", "TIMEOUT",
        "CONNECT_FAILED", "INVALID_RESPONSE", "FAILED"
      }) {
        String code = "ADB_" + stage + "_BRIDGE_" + reason;
        if (output.contains(code)) return code;
      }
    if (output.contains("ENVIRONMENT_BUSY")) return "ADB_ENVIRONMENT_BUSY";
    if (output.contains("Native virtual-screen launch ticket missing")) return "ADB_TICKET_MISSING";
    if (output.contains("Invalid native virtual-screen ticket")) return "ADB_TICKET_INVALID";
    if (output.contains("[POLICY_BLOCKED]")) return "ADB_POLICY_BLOCKED";
    if (output.contains("ADB_PROCESS_EXIT_UNCONFIRMED")) return "ADB_PROCESS_EXIT_UNCONFIRMED";
    if (output.contains("EXECUTION_UNKNOWN")) return "ADB_EXECUTION_UNKNOWN";
    if (output.contains("CONNECT_FAIL:")) return "ADB_CONNECT_FAILED";
    if (output.contains("NO_KEY:")) return "ADB_PAIRING_KEY_MISSING";
    if (output.contains("DEPS_MISSING:")) return "ADB_DEPENDENCIES_MISSING";
    if (output.contains("INVALID_ARGUMENT:")) return "ADB_INVALID_ARGUMENT";
    if (output.contains("ADB_CANCELLED:")) return "ADB_CANCELLED";
    if (output.contains("ADB_ERROR:")) return "ADB_PROCESS_FAILED";
    return "";
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
