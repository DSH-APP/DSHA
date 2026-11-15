package com.deepseekharness.app.util;

/** Manage getaddrinfo ordering only when the guest has no administrator policy. */
public final class GaiConfig {
  private GaiConfig() {}

  // Once any precedence is specified glibc replaces its entire default table.
  // Keep the other default entries and raise IPv4-mapped addresses above IPv6.
  private static final String OWNED =
      "# DSHA managed address precedence begin\n"
          + "precedence ::1/128 50\n"
          + "precedence ::/0 40\n"
          + "precedence 2002::/16 30\n"
          + "precedence ::/96 20\n"
          + "precedence ::ffff:0:0/96 100\n"
          + "# DSHA managed address precedence end\n";

  public static String reconcile(String original, String mode) {
    String text = original == null ? "" : original;
    // Only remove our exact unmodified block. A modified block belongs to the user.
    text = text.replace(OWNED, "").replace(OWNED.replace("\n", "\r\n"), "");
    if ("native".equals(ResolverConfig.mode(mode))) return text;
    // Any active configuration is user-owned, even an unrecognized directive.
    for (String line : text.split("\\R")) {
      if (!line.trim().isEmpty() && !line.trim().startsWith("#")) return text;
    }
    // Do not alter user bytes by inserting a separator that could not be removed later.
    if (!text.isEmpty() && !text.endsWith("\n")) return text;
    return text + OWNED;
  }
}
