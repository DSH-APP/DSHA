package com.deepseekharness.app.util;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;

/** RFC 1918 address selection; a public 172.* address is not a private LAN fallback. */
public final class LanAddressPolicy {
  private LanAddressPolicy() {}

  /** Interface-name hints select an address to display; they do not prove remote reachability. */
  public record Candidate(
      String interfaceName, String address, boolean up, boolean loopback, boolean pointToPoint) {}

  static int priority(String name) {
    if (name == null) return -1;
    name = name.toLowerCase(java.util.Locale.ROOT);
    if (name.startsWith("wlan") || name.startsWith("wifi")) return 2;
    if (name.startsWith("eth")) return 1;
    // A private cellular, tunnel, radio or unknown interface is not evidence of
    // an address another LAN device can use. Do not advertise it as a fallback.
    return -1;
  }

  public static String choose(Iterable<Candidate> candidates) {
    Candidate selected = null;
    int best = -1;
    for (Candidate candidate : candidates) {
      if (candidate == null
          || !candidate.up
          || candidate.loopback
          || candidate.pointToPoint
          || !privateIpv4(candidate.address)) continue;
      int rank = priority(candidate.interfaceName);
      if (rank > best) {
        best = rank;
        selected = candidate;
      }
    }
    return selected == null ? null : selected.address;
  }

  /** Conservative display discovery; OEM names/VPN routing still require actual-device checks. */
  public static String currentPrivateAddress() {
    try {
      List<Candidate> candidates = new ArrayList<>();
      var interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        var network = interfaces.nextElement();
        boolean up = network.isUp(),
            loopback = network.isLoopback(),
            pointToPoint = network.isPointToPoint();
        var addresses = network.getInetAddresses();
        while (addresses.hasMoreElements()) {
          var address = addresses.nextElement();
          if (address instanceof Inet4Address)
            candidates.add(
                new Candidate(
                    network.getName(), address.getHostAddress(), up, loopback, pointToPoint));
        }
      }
      return choose(candidates);
    } catch (java.net.SocketException | SecurityException unavailable) {
      return null;
    }
  }

  public static boolean privateIpv4(String address) {
    if (address == null) return false;
    String[] parts = address.split("\\.", -1);
    if (parts.length != 4) return false;
    int[] bytes = new int[4];
    for (int i = 0; i < parts.length; i++) {
      if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
      bytes[i] = Integer.parseInt(parts[i]);
      if (bytes[i] > 255) return false;
    }
    return bytes[0] == 10
        || bytes[0] == 192 && bytes[1] == 168
        || bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31;
  }
}
