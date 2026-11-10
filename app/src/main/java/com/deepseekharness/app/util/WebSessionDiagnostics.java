package com.deepseekharness.app.util;

/** Fixed failure facts only: free-form messages, URLs, process environments and paths never escape. */
public final class WebSessionDiagnostics {
  private WebSessionDiagnostics() {}

  private static final java.util.Set<String> CODES =
      java.util.Set.of(
          "WEB_SESSION_PRIVATE_DIRECTORY",
          "WEB_SESSION_PRIVATE_DIRECTORY_CHANGED",
          "WEB_SESSION_APK_IDENTITY",
          "WEB_SESSION_SCHEMA",
          "WEB_SESSION_IDENTITY",
          "WEB_SESSION_CREDENTIAL",
          "WEB_SESSION_UNREADABLE",
          "WEB_SESSION_ENCRYPTION_UNAVAILABLE",
          "WEB_SESSION_WRITE",
          "WEB_SESSION_LAUNCHER_MISSING",
          "WEB_SESSION_PID_RECORD",
          "WEB_SESSION_PID_RECORD_INVALID",
          "WEB_SESSION_GUEST_PROCESS",
          "WEB_SESSION_LAUNCHER_PID_INVALID",
          "WEB_SESSION_LAUNCHER_PROCESS",
          "WEB_SESSION_CAPTURE_VERIFY",
          "WEB_SESSION_CAPTURE_UNCONFIRMED",
          "WEB_SESSION_PROCESS_UNREADABLE",
          "WEB_SESSION_ENV_UNREADABLE",
          "WEB_SESSION_ENV_DUPLICATE",
          "WEB_SESSION_PROCESS_CHANGED",
          "WEB_SESSION_BOOT_UNREADABLE",
          "WEB_SESSION_BOOT_LIMIT",
          "WEB_SESSION_LAUNCHER_SCAN_UNREADABLE",
          "WEB_SESSION_LAUNCHER_SCAN_UNCONFIRMED",
          "WEB_SESSION_RECORD_MISSING_FOR_EXISTING_PID",
          "PARENT_LINK",
          "PARENT_LINK_OR_MISSING",
          "PERMISSION_DENIED",
          "NO_SPACE",
          "SOURCE_CHANGED",
          "DEVICE_LOCKED",
          "KEY_MISSING",
          "KEY_INVALIDATED",
          "AUTHENTICATION_REQUIRED",
          "TRANSIENT_STORE");

  public static String failure(String stage, Throwable failure) {
    String code = "UNCLASSIFIED";
    String detail = "UNCLASSIFIED";
    java.util.Set<Throwable> seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (Throwable item = failure;
        item != null && seen.size() < 8 && seen.add(item);
        item = item.getCause()) {
      String message = item.getMessage();
      if (message != null && CODES.contains(message)) {
        if (code.equals("UNCLASSIFIED")) code = message;
        detail = message;
      }
    }
    return "stage="
        + stable(stage)
        + " code="
        + code
        + " detail="
        + detail
        + " kind="
        + kind(failure);
  }

  /** 终局未确认（没有异常）也必须可导出：只保留固定阶段的稳定原因码，不带现场正文。 */
  public static String unresolved(String stage, String reason, int attempts) {
    return "stage="
        + stable(stage)
        + " reason="
        + stable(reason)
        + " attempts="
        + Math.max(0, Math.min(attempts, 9))
        + " kind=UNRESOLVED";
  }

  private static String stable(String value) {
    return value != null && value.matches("[A-Z_]{1,40}") ? value : "UNKNOWN";
  }

  private static String kind(Throwable failure) {
    if (failure instanceof java.io.FileNotFoundException) return "FILE_UNAVAILABLE";
    if (failure instanceof java.io.IOException) return "IO";
    if (failure instanceof SecurityException) return "SECURITY";
    if (failure instanceof RuntimeException) return "RUNTIME";
    return "OTHER";
  }
}
