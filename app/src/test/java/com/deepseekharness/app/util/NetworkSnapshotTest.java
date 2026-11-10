package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class NetworkSnapshotTest {
  private static NetworkSnapshot wifi(List<String> dns) {
    return new NetworkSnapshot(
        100, "wifi", false, true, false, dns, "opportunistic", "", true, false);
  }

  @Test
  public void dnsIsDedupedAndScopedLinkLocalIsDropped() {
    NetworkSnapshot snapshot =
        wifi(java.util.Arrays.asList("192.0.2.53", " 192.0.2.53 ", "fe80::1%wlan0", null, ""));
    assertEquals(List.of("192.0.2.53"), snapshot.dnsServers);
  }

  @Test
  public void equalityIgnoresNothingButFactsAndDrivesDedup() {
    assertEquals(wifi(List.of("192.0.2.53")), wifi(List.of("192.0.2.53")));
    assertEquals(wifi(List.of("192.0.2.53")).hashCode(), wifi(List.of("192.0.2.53")).hashCode());
    assertNotEquals(wifi(List.of("192.0.2.53")), wifi(List.of("198.51.100.53")));
    NetworkSnapshot cellular =
        new NetworkSnapshot(
            200, "cellular", false, true, false, List.of("10.0.0.53"), "off", "", false, true);
    assertNotEquals(wifi(List.of("10.0.0.53")), cellular);
  }

  @Test
  public void transportPrefersPhysicalAndVpnIsSeparate() {
    assertEquals("wifi", NetworkSnapshot.transport(true, true, false, false));
    assertEquals("cellular", NetworkSnapshot.transport(false, true, false, false));
    assertEquals("ethernet", NetworkSnapshot.transport(false, false, true, false));
    assertEquals("bluetooth", NetworkSnapshot.transport(false, false, false, true));
    assertEquals("other", NetworkSnapshot.transport(false, false, false, false));
    assertEquals("off", NetworkSnapshot.privateDnsMode(false, "dns.example"));
    assertEquals("opportunistic", NetworkSnapshot.privateDnsMode(true, null));
    assertEquals("strict", NetworkSnapshot.privateDnsMode(true, "dns.example"));
  }

  @Test
  public void renderUsesStableKeysAndNoAddressesBeyondDns() {
    String text =
        new NetworkSnapshot(
                7,
                "cellular",
                true,
                false,
                true,
                List.of("10.0.0.53"),
                "strict",
                "dns.example",
                false,
                true)
            .render();
    assertEquals(
        "network=cellular\nvpn=true\nvalidated=false\nblocked=true\nip=ipv6\n"
            + "device_dns=10.0.0.53\nprivate_dns=strict dns.example\n",
        text);
    String none = NetworkSnapshot.NONE.render();
    assertTrue(none.startsWith("network=none\n"));
    assertTrue(none.contains("device_dns=-\n"));
    assertFalse(NetworkSnapshot.NONE.connected());
  }
}
