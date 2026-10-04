package com.deepseekharness.app.util;

/** Preserve user DNS/search/options; add only current device DNS when none is configured. */
public final class ResolverConfig {
  private ResolverConfig() {}

  private static final String OWNED = "# DSHA managed IPv4 DNS compatibility\noptions no-aaaa\n";
  private static final String DNS_BEGIN = "# DSHA managed device DNS\n";
  private static final String DNS_END = "# DSHA end device DNS\n";
  private static final java.util.regex.Pattern MANAGED_DNS =
      java.util.regex.Pattern.compile(
          "(?m)^# DSHA managed device DNS\\r?\\n(?:nameserver [0-9A-Fa-f:.]+\\r?\\n)*# DSHA end device DNS\\r?\\n");

  public static String mode(String value) {
    return "ipv4".equals(value) || "native".equals(value) ? value : "auto";
  }

  public static String reconcile(String original, String mode) {
    return reconcile(original, mode, java.util.Collections.emptyList());
  }

  public static String reconcile(
      String original, String mode, java.util.List<String> deviceServers) {
    String text = original == null ? "" : original;
    text = text.replace(OWNED, "").replace(OWNED.replace("\n", "\r\n"), "");
    java.util.regex.Matcher managed = MANAGED_DNS.matcher(text);
    String previous = managed.find() ? managed.group() : "";
    String unmanaged = managed.replaceAll("");
    boolean nameserver = false, noAaaa = false;
    for (String line : unmanaged.split("\\R")) {
      String clean = line.split("[#;]", 2)[0].trim();
      if (clean.matches("nameserver\\s+\\S+.*")) nameserver = true;
      if (clean.matches("options\\s+.*"))
        for (String token : clean.split("\\s+")) if (token.equals("no-aaaa")) noAaaa = true;
    }
    java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
    if (deviceServers != null)
      for (String server : deviceServers) if (validServer(server)) unique.add(server);
    if (nameserver) {
      text = unmanaged;
    } else if (!unique.isEmpty()) {
      text = terminated(unmanaged) + DNS_BEGIN;
      for (String server : unique) text += "nameserver " + server + "\n";
      text += DNS_END;
    } else if (!validManagedBlock(previous)) {
      text = unmanaged;
    }
    if ("ipv4".equals(mode(mode)) && !noAaaa) text = terminated(text) + OWNED;
    return text;
  }

  private static String terminated(String value) {
    return value.isEmpty() || value.endsWith("\n") ? value : value + "\n";
  }

  private static boolean validManagedBlock(String block) {
    if (block.isEmpty()) return false;
    String[] lines = block.split("\\R");
    if (lines.length < 3) return false;
    for (int i = 1; i < lines.length - 1; i++) {
      if (!lines[i].startsWith("nameserver ") || !validServer(lines[i].substring(11))) return false;
    }
    return true;
  }

  /** Parse numeric literals without a hostname lookup or network access. */
  private static boolean validServer(String server) {
    if (server == null || server.isEmpty()) return false;
    if (server.indexOf(':') < 0) {
      String[] octets = server.split("\\.", -1);
      if (octets.length != 4) return false;
      for (String octet : octets) {
        if (octet.isEmpty() || octet.length() > 3 || octet.length() > 1 && octet.charAt(0) == '0')
          return false;
        int value = 0;
        for (int i = 0; i < octet.length(); i++) {
          char digit = octet.charAt(i);
          if (digit < '0' || digit > '9') return false;
          value = value * 10 + digit - '0';
        }
        if (value > 255) return false;
      }
      return true;
    }
    if (server.length() > 39 || !server.matches("[0-9A-Fa-f:]+")) return false;
    int compressed = server.indexOf("::");
    if (compressed < 0 && (server.startsWith(":") || server.endsWith(":"))) return false;
    if (compressed >= 0 && server.indexOf("::", compressed + 2) >= 0) return false;
    String left = compressed < 0 ? server : server.substring(0, compressed);
    String right = compressed < 0 ? "" : server.substring(compressed + 2);
    int groups = 0;
    for (String half : new String[] {left, right}) {
      if (half.isEmpty()) continue;
      for (String group : half.split(":", -1)) {
        if (group.isEmpty() || group.length() > 4) return false;
        groups++;
      }
    }
    return compressed < 0 ? groups == 8 : groups < 8;
  }
}
