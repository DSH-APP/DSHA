package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;

/** Credential-free LAN address snapshot. A configured switch is never evidence of a listener. */
public final class LanAccessPresentation {
  public enum State {
    DISABLED,
    PERMISSION_REQUIRED,
    WAITING_AUTH,
    LISTENER_UNAVAILABLE,
    DISCOVERY_DENIED,
    DISCOVERY_FAILED,
    IPV6_ONLY,
    NO_ADDRESS,
    READY
  }

  public final State state;
  public final long generation;
  public final List<String> services;

  private LanAccessPresentation(State state, long generation, List<String> services) {
    this.state = state;
    this.generation = generation;
    this.services = List.copyOf(services);
  }

  public static LanAccessPresentation capture(
      boolean enabled,
      boolean permission,
      long generation,
      boolean authenticated,
      int currentListenerPort,
      LanAddressPolicy.Discovery discovery) {
    State state;
    List<String> services = new ArrayList<>();
    if (!enabled) state = State.DISABLED;
    else if (!permission) state = State.PERMISSION_REQUIRED;
    else if (generation <= 0 || !authenticated) state = State.WAITING_AUTH;
    else if (currentListenerPort < 1 || currentListenerPort > 65535)
      state = State.LISTENER_UNAVAILABLE;
    else if (discovery == null || discovery.failure() == LanAddressPolicy.Failure.INTERFACES)
      state = State.DISCOVERY_FAILED;
    else if (discovery.failure() == LanAddressPolicy.Failure.PERMISSION)
      state = State.DISCOVERY_DENIED;
    else {
      // Even an injected or stale discovery object cannot publish a hostname,
      // loopback URL, IPv6 literal, or token as an IPv4 LAN listener address.
      for (String address : discovery.addresses())
        if (LanAddressPolicy.privateIpv4(address)) {
          String service = "http://" + address + ":" + currentListenerPort + "/";
          if (!services.contains(service)) services.add(service);
        }
      state =
          !services.isEmpty()
              ? State.READY
              : discovery.ipv6Only() ? State.IPV6_ONLY : State.NO_ADDRESS;
    }
    return new LanAccessPresentation(state, generation, services);
  }

  public boolean stillOffers(long currentGeneration, String service) {
    return state == State.READY && generation == currentGeneration && services.contains(service);
  }
}
