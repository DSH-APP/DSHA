package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class HostLookupTest {
  @Test
  public void normalizeAcceptsOnlyRealDomainNames() {
    assertEquals("api.example.com", HostLookup.normalize(" API.Example.COM. "));
    assertEquals("_srv.example.com", HostLookup.normalize("_srv.example.com"));
    assertEquals("xn--fiq228c.cn", HostLookup.normalize("xn--fiq228c.cn"));
    for (String invalid :
        new String[] {
          null,
          "",
          ".",
          "localhost",
          "svc.localhost",
          "intranet",
          "10.0.0.1",
          "1.2.3.4.5",
          "2001:db8::1",
          "[::1]",
          "a..b",
          "-bad.example",
          "bad-.example",
          "sp ace.example",
          "evil.example/../x",
          "evil.example&host=x",
          "例子.cn",
          "a".repeat(64) + ".example",
          ("a".repeat(60) + ".").repeat(5) + "com"
        }) assertNull(String.valueOf(invalid), HostLookup.normalize(invalid));
  }

  @Test
  public void familyIsStrict() {
    assertEquals(0, HostLookup.family(null));
    assertEquals(0, HostLookup.family(""));
    assertEquals(0, HostLookup.family("0"));
    assertEquals(4, HostLookup.family("4"));
    assertEquals(6, HostLookup.family("6"));
    assertEquals(-1, HostLookup.family("IPv4"));
    assertEquals(-1, HostLookup.family("46"));
  }

  @Test
  public void selectFiltersOrdersAndBounds() {
    List<String> mixed = List.of("2001:db8::1", "192.0.2.1", "192.0.2.1", "fe80::1%wlan0", "");
    assertEquals(List.of("192.0.2.1", "2001:db8::1"), HostLookup.select(mixed, 0));
    assertEquals(List.of("192.0.2.1"), HostLookup.select(mixed, 4));
    assertEquals(List.of("2001:db8::1"), HostLookup.select(mixed, 6));
    java.util.List<String> many = new java.util.ArrayList<>();
    for (int i = 0; i < 40; i++) many.add("192.0.2." + i);
    assertEquals(HostLookup.MAX_ADDRESSES, HostLookup.select(many, 0).size());
    assertEquals(List.of(), HostLookup.select(null, 0));
  }

  @Test
  public void renderHasFixedHeaderAndNeverEmitsAddressesOnFailure() {
    assertEquals(
        "DSHA_RESOLVE_V1\nstatus=OK\n4 192.0.2.1\n6 2001:db8::1\n",
        HostLookup.render(HostLookup.Status.OK, List.of("192.0.2.1", "2001:db8::1")));
    assertEquals(
        "DSHA_RESOLVE_V1\nstatus=NODATA\n", HostLookup.render(HostLookup.Status.OK, List.of()));
    String failed = HostLookup.render(HostLookup.Status.TIMEOUT, List.of("192.0.2.1"));
    assertEquals("DSHA_RESOLVE_V1\nstatus=TIMEOUT\n", failed);
    assertTrue(HostLookup.render(HostLookup.Status.INVALID, null).endsWith("status=INVALID\n"));
  }
}
