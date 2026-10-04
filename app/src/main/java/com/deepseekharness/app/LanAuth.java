package com.deepseekharness.app;

import com.deepseekharness.app.util.Query;

/**
 * Small, Android-free LAN credential and request-line helpers.
 *
 * <p>The LAN credential is deliberately separate from the dsh BrowserAuth
 * cookie and from the 3090 bridge token. Only an exact {@code token} query
 * parameter or the DSHA LAN cookie is accepted; arbitrary headers are not
 * credentials for this proxy.</p>
 */
final class LanAuth {

  static final int AUTH_DENY = 0;
  static final int AUTH_OK = 1;
  static final int AUTH_OK_SET_COOKIE = 2;

  /** Cookie owned by the DSHA LAN proxy, never by dsh itself. */
  static final String COOKIE_NAME = "dsha_lan";

  private LanAuth() {}

  /** Return the credential source for one complete request header. */
  static int tokenOk(String head, String token) {
    if (head == null || token == null || token.isEmpty()) return AUTH_DENY;
    String cookie = null;
    for (String line : head.split("\\r?\\n")) {
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      String name = line.substring(0, colon).trim();
      String value = line.substring(colon + 1).trim();
      if (!name.equalsIgnoreCase("Cookie")) continue;
      for (String part : value.split(";")) {
        String p = part.trim();
        String prefix = COOKIE_NAME + "=";
        if (p.startsWith(prefix)) {
          cookie = p.substring(prefix.length()).trim();
          break;
        }
      }
    }
    if (cookie != null && constantTimeEquals(token, cookie)) return AUTH_OK;
    String query = queryToken(head);
    return query != null && constantTimeEquals(token, query) ? AUTH_OK_SET_COOKIE : AUTH_DENY;
  }

  /** Read only the request target, never Referer or another header. */
  static String queryToken(String head) {
    if (head == null) return null;
    int nl = head.indexOf('\n');
    String line = nl >= 0 ? head.substring(0, nl) : head;
    int first = line.indexOf(' ');
    if (first < 0) return null;
    int second = line.indexOf(' ', first + 1);
    String target = second > first ? line.substring(first + 1, second) : line.substring(first + 1);
    return queryTokenFromTarget(target);
  }

  /** Read an exact, undecoded {@code token=} parameter with a name boundary. */
  static String queryTokenFromTarget(String target) {
    String raw = Query.raw(Query.of(target), "token");
    return raw == null ? null : raw.trim();
  }

  /** Remove only the LAN query credential while preserving HTTP version. */
  static String stripTokenFromRequestLine(String line) {
    if (line == null) return null;
    int first = line.indexOf(' ');
    if (first < 0) return line;
    int second = line.indexOf(' ', first + 1);
    String target = second > first ? line.substring(first + 1, second) : line.substring(first + 1);
    String tail = second > first ? line.substring(second) : "";
    int q = target.indexOf('?');
    if (q < 0) return line;
    String path = target.substring(0, q);
    String query = target.substring(q + 1);
    String fragment = "";
    int hash = query.indexOf('#');
    if (hash >= 0) {
      fragment = query.substring(hash);
      query = query.substring(0, hash);
    }
    StringBuilder kept = new StringBuilder();
    for (String item : query.split("&")) {
      if (item.isEmpty()) continue;
      int eq = item.indexOf('=');
      String name = eq > 0 ? item.substring(0, eq) : item;
      if ("token".equals(name)) continue;
      if (kept.length() > 0) kept.append('&');
      kept.append(item);
    }
    String rebuilt = kept.length() == 0 ? path + fragment : path + "?" + kept + fragment;
    return line.substring(0, first + 1) + rebuilt + tail;
  }

  /** Remove only an exact LAN {@code token=} query parameter from a URL. */
  static String stripTokenFromUrl(String url) {
    if (url == null || url.isEmpty()) return url;
    int hash = url.indexOf('#');
    String fragment = hash >= 0 ? url.substring(hash) : "";
    String base = hash >= 0 ? url.substring(0, hash) : url;
    int q = base.indexOf('?');
    if (q < 0) return url;
    String path = base.substring(0, q);
    String query = base.substring(q + 1);
    StringBuilder kept = new StringBuilder();
    for (String item : query.split("&", -1)) {
      if (item.isEmpty()) continue;
      int eq = item.indexOf('=');
      String name = eq > 0 ? item.substring(0, eq) : item;
      if ("token".equals(name)) continue;
      if (kept.length() > 0) kept.append('&');
      kept.append(item);
    }
    return kept.length() == 0 ? path + fragment : path + "?" + kept + fragment;
  }

  static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) return false;
    int diff = a.length() ^ b.length();
    for (int i = 0; i < a.length(); i++) {
      diff |= a.charAt(i) ^ (i < b.length() ? b.charAt(i) : 0);
    }
    return diff == 0;
  }
}
