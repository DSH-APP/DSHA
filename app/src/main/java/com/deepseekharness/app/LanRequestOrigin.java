package com.deepseekharness.app;

import java.util.List;

/** Check browser authority before rewriting headers to the loopback backend. */
final class LanRequestOrigin {
  private LanRequestOrigin() {}

  static boolean allowed(
      List<String> hosts,
      List<String> origins,
      List<String> sites,
      String localAddress,
      int localPort,
      boolean websocket) {
    if (hosts.size() != 1
        || origins.size() > 1
        || sites.size() > 1
        || localAddress == null
        || !localAddress.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
        || localPort < 1
        || localPort > 65535) return false;
    String expectedHost = localAddress + ":" + localPort;
    if (!expectedHost.equals(hosts.get(0))) return false;
    if (!sites.isEmpty()
        && !sites.get(0).equalsIgnoreCase("same-origin")
        && !sites.get(0).equalsIgnoreCase("none")) return false;
    if (origins.isEmpty()) return !websocket;
    return ("http://" + expectedHost).equals(origins.get(0));
  }
}
