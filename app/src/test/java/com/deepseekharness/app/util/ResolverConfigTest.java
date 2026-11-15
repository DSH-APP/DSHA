package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResolverConfigTest {
  @Test
  public void pristineBundledDnsMigratesOnlyWhenDeviceDnsIsAvailable() {
    String legacy = "nameserver 8.8.8.8\nnameserver 223.5.5.5\n";
    assertEquals(legacy, ResolverConfig.reconcile(legacy, "auto", java.util.List.of()));
    assertEquals(legacy, ResolverConfig.reconcile(legacy, "auto", java.util.List.of("bad")));
    String migrated = ResolverConfig.reconcile(legacy, "auto", java.util.List.of("192.0.2.53"));
    assertFalse(migrated.contains("8.8.8.8"));
    assertTrue(migrated.contains("nameserver 192.0.2.53\n"));
    assertEquals(migrated, ResolverConfig.reconcile(migrated, "auto", java.util.List.of()));
    assertEquals(
        migrated, ResolverConfig.reconcile(migrated, "auto", java.util.List.of("192.0.2.53")));
    assertEquals(
        migrated, ResolverConfig.reconcile(legacy, "native", java.util.List.of("192.0.2.53")));
    String changed = legacy + "# retain my DNS\n";
    assertEquals(
        changed, ResolverConfig.reconcile(changed, "auto", java.util.List.of("192.0.2.53")));
    String crlf = legacy.replace("\n", "\r\n");
    assertEquals(crlf, ResolverConfig.reconcile(crlf, "auto", java.util.List.of("192.0.2.53")));
    String reordered = "nameserver 223.5.5.5\nnameserver 8.8.8.8\n";
    assertEquals(
        reordered, ResolverConfig.reconcile(reordered, "auto", java.util.List.of("192.0.2.53")));
    String ipv4 = ResolverConfig.reconcile(legacy, "ipv4", java.util.List.of("192.0.2.53"));
    assertTrue(ipv4.contains("nameserver 192.0.2.53\n"));
    assertTrue(ipv4.contains("options no-aaaa\n"));
  }

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

  @Test
  public void networkSwitchSequenceReplacesOnlyTheManagedBlock() {
    // WiFi 下启动：私网 DNS 写进托管段
    String wifi = ResolverConfig.reconcile("", "auto", java.util.List.of("172.19.0.2"));
    assertTrue(wifi.contains("nameserver 172.19.0.2\n"));
    // 切到流量：托管段整体换成运营商 DNS，旧 WiFi DNS 不残留
    String cellular =
        ResolverConfig.reconcile(wifi, "auto", java.util.List.of("10.0.0.53", "2001:db8::53"));
    assertFalse(cellular.contains("172.19.0.2"));
    assertTrue(cellular.contains("nameserver 10.0.0.53\nnameserver 2001:db8::53\n"));
    // 断网瞬间：DNS 为空，保留上次有效段
    assertEquals(cellular, ResolverConfig.reconcile(cellular, "auto", java.util.List.of()));
    // 回到 WiFi：再次替换
    assertEquals(wifi, ResolverConfig.reconcile(cellular, "auto", java.util.List.of("172.19.0.2")));
    // 用户自己写 nameserver 后，任何切网都不覆盖
    String user = "nameserver 192.0.2.1\n" + cellular;
    String kept = ResolverConfig.reconcile(user, "auto", java.util.List.of("172.19.0.2"));
    assertTrue(kept.startsWith("nameserver 192.0.2.1\n"));
    assertFalse(kept.contains("172.19.0.2"));
    assertFalse(kept.contains("DSHA managed device DNS"));
    // ipv4 模式下切网仍保留自有 no-aaaa 段且不重复
    String v4 = ResolverConfig.reconcile(wifi, "ipv4", java.util.List.of("10.0.0.53"));
    String v4back = ResolverConfig.reconcile(v4, "ipv4", java.util.List.of("172.19.0.2"));
    assertEquals(1, v4back.split("options no-aaaa", -1).length - 1);
    assertTrue(v4back.contains("nameserver 172.19.0.2\n"));
  }

  @Test
  public void describeReportsSourceAndStaleness() {
    String wifi = ResolverConfig.reconcile("", "auto", java.util.List.of("172.19.0.2"));
    assertTrue(
        ResolverConfig.describe(wifi, java.util.List.of("172.19.0.2"))
            .contains("resolv_source=managed\nresolv_dns=172.19.0.2\nresolv_state=current\n"));
    assertTrue(
        ResolverConfig.describe(wifi, java.util.List.of("10.0.0.53"))
            .contains("resolv_state=stale"));
    assertTrue(ResolverConfig.describe(wifi, java.util.List.of()).contains("resolv_state=current"));
    String user = "nameserver 192.0.2.1\n";
    assertTrue(
        ResolverConfig.describe(user, java.util.List.of("10.0.0.53"))
            .contains("resolv_source=user\nresolv_dns=192.0.2.1\nresolv_state=user"));
    String legacy = "nameserver 8.8.8.8\nnameserver 223.5.5.5\n";
    assertTrue(
        ResolverConfig.describe(legacy, java.util.List.of("10.0.0.53"))
            .contains("resolv_source=legacy"));
    assertTrue(
        ResolverConfig.describe(legacy, java.util.List.of("10.0.0.53"))
            .contains("resolv_state=stale"));
    assertTrue(ResolverConfig.describe("", null).contains("resolv_source=none"));
    assertTrue(
        ResolverConfig.describe("# nameserver 1.1.1.1\n", null).contains("resolv_state=empty"));
  }
}
