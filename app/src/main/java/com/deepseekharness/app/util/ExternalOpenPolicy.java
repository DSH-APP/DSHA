package com.deepseekharness.app.util;

/** /app/open 的 scheme 分类；拒绝词表待 T40 的 DeviceRefusal/AgentNextAction 实现后迁移到统一接口。 */
public final class ExternalOpenPolicy {
  public enum Kind {
    SAFE,
    CONFIRM,
    FORBIDDEN
  }

  private ExternalOpenPolicy() {}

  public static Kind classify(String url) {
    if (url == null || url.isEmpty() || url.indexOf('\0') >= 0) return Kind.FORBIDDEN;
    try {
      java.net.URI uri = java.net.URI.create(url);
      String scheme = uri.getScheme();
      if (scheme == null || !scheme.matches("(?i)[a-z][a-z0-9+.-]*")) return Kind.FORBIDDEN;
      String normalized = scheme.toLowerCase(java.util.Locale.ROOT);
      if (normalized.equals("http") || normalized.equals("https"))
        return uri.getHost() == null || uri.getHost().isEmpty() ? Kind.FORBIDDEN : Kind.SAFE;
      if (normalized.equals("geo")) return Kind.SAFE;
      if (normalized.equals("tel") || normalized.equals("mailto") || normalized.equals("market"))
        return Kind.CONFIRM;
    } catch (RuntimeException ignored) {
    }
    return Kind.FORBIDDEN;
  }

  public static boolean requiresConfirmation(String url) {
    return classify(url) == Kind.CONFIRM;
  }
}
