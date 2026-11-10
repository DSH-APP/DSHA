package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.NetworkHealthPolicy.Event;
import com.deepseekharness.app.util.NetworkHealthPolicy.Probe;
import com.deepseekharness.app.util.NetworkHealthPolicy.Signals;
import com.deepseekharness.app.util.NetworkHealthPolicy.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class NetworkHealthPolicyTest {
  private static Signals healthy() {
    Signals s = new Signals();
    s.webActive = true;
    s.foregroundService = true;
    s.connected = true;
    s.validated = true;
    s.transport = "wifi";
    s.resolvState = "current";
    return s;
  }

  @Test
  public void healthyIsOk() {
    assertEquals(Verdict.OK, NetworkHealthPolicy.classify(healthy()));
    assertEquals(Verdict.OK, NetworkHealthPolicy.classify(null));
  }

  @Test
  public void priorityFgsThenBlockedThenDnsThenEgressThenFrozen() {
    Signals s = healthy();
    s.foregroundService = false;
    s.blocked = true;
    s.resolvState = "stale";
    s.probe = new Probe(false, false, 0, 0, "UnknownHostException");
    s.frozenGapMs = 600_000;
    assertEquals(Verdict.FGS_MISSING, NetworkHealthPolicy.classify(s));
    s.foregroundService = true;
    assertEquals(Verdict.SYSTEM_BLOCKED, NetworkHealthPolicy.classify(s));
    s.blocked = false;
    assertEquals(Verdict.DNS_STALE, NetworkHealthPolicy.classify(s));
    s.resolvState = "current";
    assertEquals(Verdict.EGRESS_FAILED, NetworkHealthPolicy.classify(s));
    s.probe = new Probe(true, true, 10, 200, null);
    assertEquals(Verdict.SUSPECT_FROZEN, NetworkHealthPolicy.classify(s));
  }

  @Test
  public void webStoppedNeverReportsFgsOrFrozen() {
    Signals s = healthy();
    s.webActive = false;
    s.foregroundService = false;
    s.frozenGapMs = 900_000;
    assertEquals(Verdict.OK, NetworkHealthPolicy.classify(s));
    s.blocked = true;
    assertEquals(Verdict.SYSTEM_BLOCKED, NetworkHealthPolicy.classify(s));
  }

  @Test
  public void disconnectedDoesNotBlameDnsOrEgress() {
    Signals s = healthy();
    s.connected = false;
    s.resolvState = "stale";
    s.probe = new Probe(false, false, 0, 0, "x");
    assertEquals(Verdict.OK, NetworkHealthPolicy.classify(s));
    assertFalse(NetworkHealthPolicy.shouldProbe(s));
  }

  @Test
  public void probeOnlyWhenUnexplained() {
    Signals s = healthy();
    assertFalse(NetworkHealthPolicy.shouldProbe(s));
    s.validated = false;
    assertTrue(NetworkHealthPolicy.shouldProbe(s));
    s.blocked = true;
    assertFalse(NetworkHealthPolicy.shouldProbe(s));
    s.blocked = false;
    s.foregroundService = false;
    assertFalse(NetworkHealthPolicy.shouldProbe(s));
  }

  @Test
  public void frozenGapThreshold() {
    assertEquals(0, NetworkHealthPolicy.frozenGap(0, 1_000_000));
    assertEquals(0, NetworkHealthPolicy.frozenGap(1_000, 1_000 + 120_000));
    assertEquals(200_000, NetworkHealthPolicy.frozenGap(1_000, 201_000));
    assertEquals(0, NetworkHealthPolicy.frozenGap(5_000, 1_000));
  }

  @Test
  public void dedupWithinWindowCountsOtherwiseAppends() {
    List<Event> events = NetworkHealthPolicy.append(null, 1_000, Verdict.OK, "");
    assertTrue(events.isEmpty());
    events = NetworkHealthPolicy.append(events, 1_000, Verdict.DNS_STALE, "a");
    events = NetworkHealthPolicy.append(events, 60_000, Verdict.DNS_STALE, "b");
    assertEquals(1, events.size());
    assertEquals(2, events.get(0).count);
    assertEquals(1_000, events.get(0).at);
    assertEquals("b", events.get(0).evidence);
    events = NetworkHealthPolicy.append(events, 70_000, Verdict.SYSTEM_BLOCKED, "c");
    events = NetworkHealthPolicy.append(events, 80_000, Verdict.DNS_STALE, "d");
    // 中间有其它结论时仍并入最近一条同结论
    assertEquals(2, events.size());
    assertEquals(3, events.get(0).count);
    events =
        NetworkHealthPolicy.append(
            events, 1_000 + NetworkHealthPolicy.DEDUP_MS, Verdict.DNS_STALE, "e");
    assertEquals(3, events.size());
  }

  @Test
  public void ringKeepsNewest() {
    List<Event> events = new ArrayList<>();
    for (int i = 0; i < NetworkHealthPolicy.MAX_EVENTS + 5; i++)
      events =
          NetworkHealthPolicy.append(
              events, (long) i * NetworkHealthPolicy.DEDUP_MS, Verdict.EGRESS_FAILED, "n" + i);
    assertEquals(NetworkHealthPolicy.MAX_EVENTS, events.size());
    assertEquals("n5", events.get(0).evidence);
    assertEquals(
        "n" + (NetworkHealthPolicy.MAX_EVENTS + 4),
        NetworkHealthPolicy.recent(events, 1).get(0).evidence);
  }

  @Test
  public void serializeRoundTripAndSkipsBadLines() {
    List<Event> events = NetworkHealthPolicy.append(null, 42, Verdict.FGS_MISSING, "web=1 fgs=0");
    String text = NetworkHealthPolicy.serialize(events) + "garbage\n7|NOPE|1|x\n";
    List<Event> parsed = NetworkHealthPolicy.parse(text);
    assertEquals(1, parsed.size());
    assertEquals(Verdict.FGS_MISSING, parsed.get(0).verdict);
    assertEquals("web=1 fgs=0", parsed.get(0).evidence);
    assertTrue(NetworkHealthPolicy.parse("random").isEmpty());
    assertTrue(NetworkHealthPolicy.parse(null).isEmpty());
  }

  @Test
  public void evidenceHasNoAddressesOrInjectedSeparators() {
    Signals s = healthy();
    s.transport = "wifi|1.2.3.4\nx";
    s.probe = new Probe(true, false, 12, 0, "javax.net.ssl.SSLHandshakeException: host 1.2.3.4");
    String evidence = NetworkHealthPolicy.evidence(s);
    assertFalse(evidence.contains("|"));
    assertFalse(evidence.contains("\n"));
    assertFalse(evidence.contains("1.2.3.4"));
    assertTrue(evidence.contains("err=SSLHandshakeException"));
    assertTrue(evidence.contains("dns=12ms"));
    assertTrue(evidence.contains("https=fail"));
  }

  @Test
  public void resolvStateStaleWinsAcrossRoots() {
    assertEquals("unavailable", NetworkHealthPolicy.resolvState(null));
    assertEquals(
        "current", NetworkHealthPolicy.resolvState("resolv_state=current\nresolv_state=current\n"));
    assertEquals(
        "stale", NetworkHealthPolicy.resolvState("resolv_state=current\nresolv_state=stale\n"));
    assertEquals("user", NetworkHealthPolicy.resolvState("resolv_state=user\n"));
  }

  @Test
  public void redactRemovesDnsAddressesAndPrivateServer() {
    String report =
        "DSHA_NET_V1\nnetwork=wifi\ndevice_dns=172.19.0.2\nprivate_dns=strict dns.example.org\n"
            + "resolv_dns=172.19.0.2\nresolv_state=current\n";
    String out = NetworkHealthPolicy.redactNetReport(report);
    assertFalse(out.contains("172.19.0.2"));
    assertFalse(out.contains("dns.example.org"));
    assertTrue(out.contains("private_dns=strict\n"));
    assertTrue(out.contains("resolv_state=current"));
  }

  @Test
  public void totalsSumCounts() {
    List<Event> events = NetworkHealthPolicy.append(null, 0, Verdict.DNS_STALE, "");
    events = NetworkHealthPolicy.append(events, 1, Verdict.DNS_STALE, "");
    events = NetworkHealthPolicy.append(events, 2, Verdict.SYSTEM_BLOCKED, "");
    assertEquals(Integer.valueOf(2), NetworkHealthPolicy.totals(events).get(Verdict.DNS_STALE));
    assertEquals(
        Integer.valueOf(1), NetworkHealthPolicy.totals(events).get(Verdict.SYSTEM_BLOCKED));
  }
}
