package com.deepseekharness.app.util;

import java.net.URI;

/** Fixed Platform authorization navigation from the current native DSH document. */
public final class DeepSeekOAuthNavigation {
  private DeepSeekOAuthNavigation() {}

  /**
   * The official account UI navigates after an asynchronous Host response, when the original
   * browser gesture has expired. Only this one HTTPS destination receives that exception.
   * Callback URLs, authorization codes, state and PKCE stay in the official backend.
   */
  public static boolean allowed(String baseUrl, String currentDocument, String destination) {
    if (!WebPreviewPolicy.sameService(baseUrl, currentDocument)
        || destination == null
        || destination.length() > 65536) return false;
    try {
      URI uri = new URI(destination);
      return "https".equals(uri.getScheme())
          && "platform.deepseek.com".equalsIgnoreCase(uri.getHost())
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && uri.getRawUserInfo() == null
          && "/dsh/authorize".equals(uri.getRawPath())
          && uri.getRawFragment() == null;
    } catch (Exception ignored) {
      return false;
    }
  }
}
