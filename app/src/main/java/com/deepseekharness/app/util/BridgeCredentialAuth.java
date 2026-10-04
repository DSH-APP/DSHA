package com.deepseekharness.app.util;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Device bridge credentials are headers, not URL parameters or cookies. */
public final class BridgeCredentialAuth {
  private BridgeCredentialAuth() {}

  public static boolean authorized(String target, List<String> headers, String expected) {
    if (expected == null
        || expected.isEmpty()
        || headers == null
        || headers.size() != 1
        || headers.get(0) == null) return false;
    for (String pair : Query.of(target).split("&")) {
      String name = pair.split("=", 2)[0];
      try {
        name = java.net.URLDecoder.decode(name, "UTF-8");
      } catch (Exception malformed) {
        return false;
      }
      if (name.equalsIgnoreCase("token")) return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), headers.get(0).getBytes(StandardCharsets.UTF_8));
  }
}
