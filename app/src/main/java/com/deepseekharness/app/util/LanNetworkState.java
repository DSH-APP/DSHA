package com.deepseekharness.app.util;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.*;
import java.util.function.Supplier;

/** Local interface identity only; no routing, credentials or remote probes. */
public final class LanNetworkState {
  private final Supplier<Set<String>> source;
  private Set<String> previous;

  public LanNetworkState() {
    this(LanNetworkState::capture);
  }

  LanNetworkState(Supplier<Set<String>> source) {
    this.source = Objects.requireNonNull(source);
  }

  /** Unknown observations preserve the last known state and do not cause churn. */
  public synchronized boolean poll() {
    Set<String> current = source.get();
    if (current == null) return false;
    current = Set.copyOf(current);
    boolean changed = previous != null && !previous.equals(current);
    previous = current;
    return changed;
  }

  private static Set<String> capture() {
    try {
      Set<String> result = new HashSet<>();
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      if (interfaces == null) return null;
      while (interfaces.hasMoreElements()) {
        NetworkInterface network = interfaces.nextElement();
        if (!network.isUp() || network.isLoopback() || network.isPointToPoint()) continue;
        String name = network.getName();
        if (name == null) continue;
        // Cellular/VPN transitions do not retire a still usable LAN connection.
        if (name.matches("(?i)(rmnet|v4-rmnet|ccmni|pdp|wwan|tun|tap|ppp).*")) continue;
        var addresses = network.getInetAddresses();
        while (addresses.hasMoreElements()) {
          var address = addresses.nextElement();
          if (address instanceof Inet4Address
              && LanAddressPolicy.privateIpv4(address.getHostAddress()))
            result.add(name + ":" + network.getIndex() + ":" + address.getHostAddress());
        }
      }
      return result;
    } catch (java.net.SocketException | SecurityException unknown) {
      return null;
    }
  }
}
