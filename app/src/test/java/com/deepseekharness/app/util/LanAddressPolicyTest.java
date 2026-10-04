package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class LanAddressPolicyTest {
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
