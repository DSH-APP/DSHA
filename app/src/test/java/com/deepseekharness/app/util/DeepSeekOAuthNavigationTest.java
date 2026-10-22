package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeepSeekOAuthNavigationTest {
  private final String base = "http://127.0.0.1:41821/";
  private final String source = base + "settings/account";
  private final String target =
      "https://platform.deepseek.com/dsh/authorize?authorize_id=synthetic";

  @Test
  public void currentNativeDocumentCanOpenOnlyTheFixedPlatformAuthorizationPath() {
    assertTrue(DeepSeekOAuthNavigation.allowed(base, source, target));
    assertTrue(
        DeepSeekOAuthNavigation.allowed(
            base, source, "https://platform.deepseek.com:443/dsh/authorize?theme=dark"));
    assertTrue(
        DeepSeekOAuthNavigation.allowed(
            base, source, "https://PLATFORM.DEEPSEEK.COM/dsh/authorize"));
  }

  @Test
  public void foreignOrStaleDocumentsCannotRequestGesturelessNavigation() {
    for (String value :
        new String[] {
          null,
          "",
          "about:blank",
          "https://evil.example/",
          "http://127.0.0.1:41822/",
          "http://localhost:41821/",
          "http://user@127.0.0.1:41821/",
          "http://127.0.0.1.evil:41821/",
          "https://127.0.0.1:41821/"
        }) {
      assertFalse(String.valueOf(value), DeepSeekOAuthNavigation.allowed(base, value, target));
    }
    assertFalse(DeepSeekOAuthNavigation.allowed(null, source, target));
  }

  @Test
  public void callbacksOtherPortsAndLookalikeDestinationsReceiveNoException() {
    for (String value :
        new String[] {
          null,
          "",
          "http://platform.deepseek.com/dsh/authorize",
          "https://platform.deepseek.com:8443/dsh/authorize",
          "https://evil.example/dsh/authorize",
          "https://platform.deepseek.com.evil/dsh/authorize",
          "https://platform.deepseek.com./dsh/authorize",
          "https://user@platform.deepseek.com/dsh/authorize",
          "https://platform.deepseek.com/dsh/authorized",
          "https://platform.deepseek.com/dsh/authorize/",
          "https://platform.deepseek.com/dsh/%61uthorize",
          "https://platform.deepseek.com/dsh/x/../authorize",
          "https://platform.deepseek.com/dsh/authorize#fragment",
          "http://127.0.0.1:41821/oauth/callback?code=synthetic&state=synthetic",
          "javascript:alert(1)",
          "intent://platform.deepseek.com/dsh/authorize"
        }) {
      assertFalse(String.valueOf(value), DeepSeekOAuthNavigation.allowed(base, source, value));
    }
    assertFalse(DeepSeekOAuthNavigation.allowed(base, source, target + "x".repeat(65536)));
  }
}
