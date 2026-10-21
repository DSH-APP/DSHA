package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class LanAddressPolicyTest {
  private static LanAddressPolicy.Candidate candidate(String name, String address) {
    return new LanAddressPolicy.Candidate(name, address, true, false, false);
  }

  @Test
  public void wifiWinsOverEarlierEthernetCellularAndTunnelCandidates() {
    var candidates =
        java.util.List.of(
            candidate("rmnet_data0", "10.0.0.2"),
            candidate("tun0", "10.1.0.2"),
            candidate("eth0", "192.168.10.2"),
            candidate("wlan0", "192.168.1.2"));
    assertEquals("192.168.1.2", LanAddressPolicy.choose(candidates));
  }

  @Test
  public void ethernetWorksWithoutWifiButUnknownPrivateInterfaceDoesNotBecomeFallback() {
    assertEquals(
        "10.2.0.2",
        LanAddressPolicy.choose(
            java.util.List.of(candidate("radio0", "10.0.0.2"), candidate("eth0", "10.2.0.2"))));
    assertNull(
        LanAddressPolicy.choose(
            java.util.List.of(
                candidate("radio0", "10.0.0.2"), candidate("rmnet_data0", "10.1.0.2"),
                candidate("tun0", "10.3.0.2"), candidate("vendor-unknown", "192.168.2.2"))));
  }

  @Test
  public void downLoopbackPointToPointAndNonprivateCandidatesAreUnavailable() {
    assertNull(
        LanAddressPolicy.choose(
            java.util.List.of(
                new LanAddressPolicy.Candidate("wlan0", "192.168.1.2", false, false, false),
                new LanAddressPolicy.Candidate("wifi0", "192.168.1.3", true, true, false),
                new LanAddressPolicy.Candidate("eth0", "10.0.0.2", true, false, true),
                candidate("wlan1", "172.32.0.2"))));
  }

  @Test
  public void acceptsExactPrivateRanges() {
    for (String ip :
        new String[] {"10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1"})
      assertTrue(ip, LanAddressPolicy.privateIpv4(ip));
  }

  @Test
  public void rejectsPublicLoopbackAndMalformedAddresses() {
    for (String ip :
        new String[] {
          "172.0.0.1",
          "172.15.255.255",
          "172.32.0.1",
          "172.99.1.1",
          "192.169.1.1",
          "127.0.0.1",
          "169.254.1.1",
          "::1",
          "10.256.0.1",
          "10.0.0",
          "10.0.0.01",
          " 10.0.0.1",
          "10.0.0.1:3081",
          "10.0.0.1\n"
        }) assertFalse(ip, LanAddressPolicy.privateIpv4(ip));
    assertFalse(LanAddressPolicy.privateIpv4(null));
  }
}
