package com.deepseekharness.app.util;

import java.util.Locale;
import java.util.UUID;

/** Backup names describe an application artifact; import still verifies its actual container. */
public final class BackupFileNames {
  public static final String EXTENSION = ".tar.gz";
  public static final String MIME_TYPE = "application/gzip";

  private BackupFileNames() {}

  public static String portableName() {
    return "DSHA-data-v5-" + UUID.randomUUID() + EXTENSION;
  }

  public static String exportMimeType(String name) {
    String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(EXTENSION)) return MIME_TYPE;
    if (lower.endsWith(".txt")) return "text/plain";
    if (lower.endsWith(".json")) return "application/json";
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
    if (lower.endsWith(".gif")) return "image/gif";
    if (lower.endsWith(".webp")) return "image/webp";
    if (lower.endsWith(".pdf")) return "application/pdf";
    if (lower.endsWith(".zip")) return "application/zip";
    if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".gz"))
      return "application/gzip";
    if (lower.endsWith(".tar")) return "application/x-tar";
    return "application/octet-stream";
  }

  /** Only complete legacy names are auto-discovered; partial legacy scopes stay explicit. */
  public static boolean discoverable(String name) {
    if (name == null) return false;
    String normalized = name.toLowerCase(Locale.ROOT).replaceAll("\\s*\\(\\d+\\)", "");
    if (normalized.matches("dsha-data-v5-[a-f0-9-]{36}\\.(?:tar\\.gz|dshbak)")) return true;
    if (!normalized.startsWith("dsha-backup-") && !normalized.startsWith("dsha-migration-"))
      return false;
    return normalized.endsWith(".dshbak")
        || normalized.endsWith(".tar.gz")
        || normalized.endsWith(".tgz")
        || normalized.endsWith(".tar");
  }
}
