package com.deepseekharness.app.util;

import java.net.URI;
import java.util.Locale;

/** Current official metadata, release-page and artifact origins; APK identity is verified separately. */
public final class UpdateLocations {
  public static final String FEED = "https://dsha.cc/api/updates.json";
  public static final String DEFAULT_PAGE = "https://dsha.cc/download/";

  private UpdateLocations() {}

  private static URI parse(String value) {
    try {
      if (value == null || value.length() > 8192) return null;
      URI uri = new URI(value);
      String path = uri.getPath(), raw = uri.getRawPath().toLowerCase(Locale.ROOT);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getFragment() != null
          || uri.getPort() != -1 && uri.getPort() != 443
          || path == null
          || path.indexOf('\\') >= 0
          || path.contains("//")
          || raw.contains("%2f")
          || raw.contains("%5c")
          || raw.contains("%25")) return null;
      for (String part : path.split("/", -1))
        if (part.equals(".") || part.equals("..")) return null;
      for (int i = 0; i < path.length(); i++)
        if (path.charAt(i) < 32 || path.charAt(i) == 127) return null;
      return uri;
    } catch (Exception invalid) {
      return null;
    }
  }

  private static boolean host(URI uri, String name) {
    return uri != null && name.equalsIgnoreCase(uri.getHost());
  }

  public static boolean feed(String value) {
    URI uri = parse(value);
    return host(uri, "dsha.cc")
        && "/api/updates.json".equals(uri.getPath())
        && uri.getRawQuery() == null;
  }

  public static boolean page(String value) {
    URI uri = parse(value);
    if (uri == null || uri.getRawQuery() != null) return false;
    String path = uri.getPath();
    if (host(uri, "dsha.cc"))
      return path.equals("/") || path.equals("/download") || path.equals("/download/");
    String lower = path.toLowerCase(Locale.ROOT), prefix = "/dsh-app/dsha/releases";
    return host(uri, "github.com")
        && (lower.equals(prefix)
            || lower.equals(prefix + "/")
            || lower.equals(prefix + "/latest")
            || lower.startsWith(prefix + "/tag/") && lower.length() > prefix.length() + 5);
  }

  public static boolean artifact(String value) {
    URI uri = parse(value);
    if (uri == null) return false;
    String path = uri.getPath().toLowerCase(Locale.ROOT);
    return path.endsWith(".apk")
        && (host(uri, "dsha.cc") && path.startsWith("/downloads/")
            || host(uri, "github.com") && path.startsWith("/dsh-app/dsha/releases/download/"));
  }

  public static String releasePage(String value) {
    return page(value) ? value : DEFAULT_PAGE;
  }

  public static boolean redirect(String original, String next) {
    if (feed(original)) return feed(next);
    if (!artifact(original)) return false;
    if (artifact(next)) return true;
    URI uri = parse(next);
    return (host(uri, "release-assets.githubusercontent.com")
            || host(uri, "objects.githubusercontent.com"))
        && uri.getPath().startsWith("/github-production-release-asset");
  }

  /** Native UI renders this as plain text; controls cannot spoof surrounding status. */
  public static String notes(String value) {
    if (value == null) return "";
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length() && out.length() < 8192; i++) {
      char c = value.charAt(i);
      if (c == '\r') {
        if (i + 1 < value.length() && value.charAt(i + 1) == '\n') i++;
        out.append('\n');
      } else if (c == '\n'
          || c == '\t'
          || c >= 32 && c != 127 && !(c >= 0x202a && c <= 0x202e) && !(c >= 0x2066 && c <= 0x2069))
        out.append(c);
    }
    return out.toString();
  }
}
