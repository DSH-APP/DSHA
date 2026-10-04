package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResolverConfigTest {
  @Test
  public void upgradePreservesCustomDnsAndSearchAndIsIdempotent() {
    String old = "nameserver 192.0.2.53\nsearch private.example\noptions timeout:2 attempts:2\n";
    String patched = ResolverConfig.reconcile(old, "ipv4");
    assertTrue(patched.startsWith(old));
    assertTrue(patched.contains("options no-aaaa"));
    assertEquals(patched, ResolverConfig.reconcile(patched, "ipv4"));
    assertEquals(old, ResolverConfig.reconcile(patched, "auto"));
    assertEquals(old, ResolverConfig.reconcile(patched, "native"));
  }

  @Test
  public void commentsAreNotNameserversOrNoAaaaDirectives() {
    String patched =
        ResolverConfig.reconcile(
            "# nameserver x\n# options no-aaaa\n", "ipv4", java.util.List.of("192.0.2.53"));
    assertTrue(patched.contains("\nnameserver 192.0.2.53\n"));
    assertTrue(patched.endsWith("options no-aaaa\n"));
  }

  @Test
  public void noDeviceDnsDoesNotInjectPublicServersOrChangeUserEntries() {
    assertEquals("", ResolverConfig.reconcile("", "auto"));
    assertEquals(
        "search private.example\n", ResolverConfig.reconcile("search private.example\n", "auto"));
    assertEquals(
        "nameserver 192.0.2.53\n",
        ResolverConfig.reconcile(
            "nameserver 192.0.2.53\n", "auto", java.util.List.of("198.51.100.3")));
    String managed =
        ResolverConfig.reconcile(
            "", "auto", java.util.List.of("192.0.2.53", "192.0.2.53", "bad host"));
    assertEquals(1, managed.split("nameserver 192.0.2.53", -1).length - 1);
    assertEquals(
        managed, ResolverConfig.reconcile(managed, "auto", java.util.List.of("192.0.2.53")));
    assertEquals(managed, ResolverConfig.reconcile(managed, "auto"));
  }

  @Test
  public void transientMissingDeviceDnsPreservesLastEffectiveManagedServers() {
    String old =
        ResolverConfig.reconcile(
            "search private.example\noptions timeout:2\n", "auto", java.util.List.of("192.0.2.53"));
    assertEquals(old, ResolverConfig.reconcile(old, "auto", java.util.List.of()));
    assertEquals(old, ResolverConfig.reconcile(old, "auto", null));
    assertEquals(old, ResolverConfig.reconcile(old, "auto", java.util.List.of("a", ":::")));

    String refreshed = ResolverConfig.reconcile(old, "auto", java.util.List.of("2001:db8::53"));
    assertTrue(refreshed.contains("nameserver 2001:db8::53\n"));
    assertFalse(refreshed.contains("nameserver 192.0.2.53\n"));
    assertTrue(refreshed.startsWith("search private.example\noptions timeout:2\n"));
    assertEquals(refreshed, ResolverConfig.reconcile(refreshed, "auto", java.util.List.of()));
  }

  @Test
  public void explicitUserNameserverAlwaysOverridesManagedDeviceDns() {
    String old = ResolverConfig.reconcile("", "auto", java.util.List.of("192.0.2.53"));
    String custom = "nameserver 198.51.100.42\nsearch private.example\n";
    assertEquals(
        custom, ResolverConfig.reconcile(custom + old, "auto", java.util.List.of("203.0.113.8")));
    assertEquals(custom, ResolverConfig.reconcile(custom + old, "auto", java.util.List.of()));
  }

  @Test
  public void onlyNumericIpv4AndIpv6LiteralsEnterManagedBlock() {
    String managed =
        ResolverConfig.reconcile(
            "",
            "auto",
            java.util.List.of(
                "a",
                "10.0.0.999",
                ":::",
                "1.2.3",
                "1.2.3.04",
                "example.com",
                "192.0.2.53",
                "2001:db8::53",
                "0:0:0:0:0:0:0:1"));
    assertTrue(managed.contains("nameserver 192.0.2.53\n"));
    assertTrue(managed.contains("nameserver 2001:db8::53\n"));
    assertTrue(managed.contains("nameserver 0:0:0:0:0:0:0:1\n"));
    for (String invalid :
        java.util.List.of("a", "10.0.0.999", ":::", "1.2.3", "1.2.3.04", "example.com"))
      assertFalse(managed.contains("nameserver " + invalid + "\n"));
    assertFalse(managed.contains("8.8.8.8"));
  }

  @Test
  public void automaticModePreservesIpv6AndExplicitUserChoices() {
    String custom = "nameserver 2001:db8::53\noptions no-aaaa edns0\n";
    assertEquals(custom, ResolverConfig.reconcile(custom, "auto"));
    assertEquals(custom, ResolverConfig.reconcile(custom, "native"));
    assertFalse(ResolverConfig.reconcile("", "auto").contains("no-aaaa"));
    assertEquals("auto", ResolverConfig.mode(null));
  }

  @Test
  public void missingFinalNewlineAndCrLfRemainValid() {
    String text = ResolverConfig.reconcile("nameserver 192.0.2.53", "ipv4");
    assertTrue(text.contains("53\n#"));
    String windows = text.replace("\n", "\r\n");
    assertFalse(ResolverConfig.reconcile(windows, "native").contains("no-aaaa"));
  }
}
