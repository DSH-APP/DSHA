package com.deepseekharness.app.util;

import java.net.URI;

/** Locale bridge allow-list for the independent emergency browser origin. */
public final class RecoveryLocalePolicy {
  private RecoveryLocalePolicy() {}

  public static final class Launch {
    public final String origin, authUrl;

    private Launch(String origin, String authUrl) {
      this.origin = origin;
      this.authUrl = authUrl;
    }
  }

  /** Keep WebView and Gecko on the same emergency-only localhost origin. */
  public static Launch launch(String formalBase, String formalAuth) {
    String localBase = toLocalhostUrl(formalBase), localAuth = toLocalhostUrl(formalAuth);
    String origin = localhostBase(localBase);
    return origin != null && localAuth != null && sameOrigin(origin, localAuth)
        ? new Launch(origin, localAuth)
        : null;
  }

  /** Convert only the signed local launch host; preserve the raw path/query/fragment bytes. */
  public static String toLocalhostUrl(String value) {
    URI uri = parse(value);
    if (uri == null || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1) return null;
    String authority = uri.getRawAuthority();
    int marker = value.indexOf("://") + 3;
    if (marker < 3
        || authority == null
        || !value.regionMatches(marker, authority, 0, authority.length())) return null;
    String suffix = value.substring(marker + authority.length());
    return "http://localhost:" + uri.getPort() + suffix;
  }

  /** Emergency pages use an exact localhost authority and never inherit the formal 127.0.0.1 origin. */
  public static String localhostBase(String value) {
    URI uri = parse(value);
    if (uri == null || !"localhost".equalsIgnoreCase(uri.getHost()) || uri.getPort() < 1)
      return null;
    return "http://localhost:" + uri.getPort() + "/";
  }

  public static boolean sameOrigin(String expectedBase, String candidate) {
    URI base = parse(expectedBase), source = parse(candidate);
    return base != null
        && source != null
        && "localhost".equalsIgnoreCase(base.getHost())
        && "localhost".equalsIgnoreCase(source.getHost())
        && base.getPort() > 0
        && base.getPort() == source.getPort();
  }

  /** 旧 WebView 只在可信应急主帧完成后补语言桥；早期兼容仍由受管 HTML 提供。 */
  public static boolean needsFinishedPageLanguageBridge(
      String expectedBase, String pageUrl, boolean documentStartSupported) {
    return !documentStartSupported && sameOrigin(expectedBase, pageUrl);
  }

  public static boolean supportedLanguage(String language) {
    return "zh".equals(language) || "en".equals(language);
  }

  /** Values are fixed to zh/en before they reach this script. */
  public static String pageScript(String language) {
    if (!supportedLanguage(language)) throw new IllegalArgumentException("RECOVERY_LANGUAGE");
    return "if(location.protocol==='http:'&&location.hostname==='localhost'){"
        + "window.__DSHA_LANGUAGE__='"
        + language
        + "';"
        + "window.dispatchEvent(new CustomEvent('dsha-language'));}";
  }

  private static URI parse(String value) {
    try {
      URI uri = new URI(value);
      if (!"http".equalsIgnoreCase(uri.getScheme())
          || uri.getRawUserInfo() != null
          || uri.getHost() == null
          || uri.getPort() < 1
          || uri.getPort() > 65535) return null;
      return uri;
    } catch (Exception invalid) {
      return null;
    }
  }
}
