package com.deepseekharness.app.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;

/** RFC 1918 address selection; a public 172.* address is not a private LAN fallback. */
public final class LanAddressPolicy {
  private LanAddressPolicy() {}

  /** Interface-name hints select an address to display; they do not prove remote reachability. */
  public record Candidate(
      String interfaceName, String address, boolean up, boolean loopback, boolean pointToPoint) {}

  public enum Failure {
    NONE,
    PERMISSION,
    INTERFACES
  }

  /** No credentials; IPv6 is observed separately because the LAN listener is IPv4-only. */
  public record Discovery(List<String> addresses, boolean ipv6Only, Failure failure) {
    public Discovery {
      addresses = List.copyOf(addresses);
    }
  }

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
    List<String> addresses = discover(candidates).addresses();
    return addresses.isEmpty() ? null : addresses.get(0);
  }

  public static Discovery discover(Iterable<Candidate> candidates) {
    List<Candidate> usable = new ArrayList<>();
    boolean ipv6 = false;
    for (Candidate candidate : candidates) {
      if (candidate == null
          || !candidate.up
          || candidate.loopback
          || candidate.pointToPoint
          || priority(candidate.interfaceName) < 0) continue;
      if (privateIpv4(candidate.address)) usable.add(candidate);
      else if (candidate.address != null && candidate.address.indexOf(':') >= 0) {
        try {
          // A numeric IPv6 literal never needs a DNS lookup. Do not infer IPv6
          // listener support or manufacture an IPv6 URL from its presence.
          String literal = candidate.address.split("%", 2)[0];
          if (!literal.matches("[0-9a-fA-F:]+")) continue;
          var address = java.net.InetAddress.getByName(literal);
          if (address instanceof Inet6Address
              && !address.isAnyLocalAddress()
              && !address.isLoopbackAddress()
              && !address.isMulticastAddress()) ipv6 = true;
        } catch (java.net.UnknownHostException invalid) {
          // A malformed candidate cannot become a usable address.
        }
      }
    }
    usable.sort(
        java.util.Comparator.comparingInt((Candidate c) -> priority(c.interfaceName))
            .reversed()
            .thenComparing(Candidate::interfaceName)
            .thenComparing(Candidate::address));
    List<String> addresses = new ArrayList<>();
    for (Candidate candidate : usable)
      if (!addresses.contains(candidate.address)) addresses.add(candidate.address);
    return new Discovery(addresses, addresses.isEmpty() && ipv6, Failure.NONE);
  }

  /** Conservative display discovery; OEM names/VPN routing still require actual-device checks. */
  public static String currentPrivateAddress() {
    List<String> addresses = currentDiscovery().addresses();
    return addresses.isEmpty() ? null : addresses.get(0);
  }

  /** Preserve discovery failures so UI can distinguish a denied read from no Wi-Fi address. */
  public static Discovery currentDiscovery() {
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
          if (address instanceof Inet4Address || address instanceof Inet6Address)
            candidates.add(
                new Candidate(
                    network.getName(), address.getHostAddress(), up, loopback, pointToPoint));
        }
      }
      return discover(candidates);
    } catch (SecurityException denied) {
      return new Discovery(List.of(), false, Failure.PERMISSION);
    } catch (java.net.SocketException unavailable) {
      return new Discovery(List.of(), false, Failure.INTERFACES);
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
