package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public final class LanAccessPresentationTest {
  private static final LanAddressPolicy.Discovery ADDRESSES =
      new LanAddressPolicy.Discovery(
          List.of("192.168.1.2", "10.2.0.2"), false, LanAddressPolicy.Failure.NONE);

  private static LanAccessPresentation capture(
      boolean enabled,
      boolean permission,
      long generation,
      boolean auth,
      int port,
      LanAddressPolicy.Discovery discovery) {
    return LanAccessPresentation.capture(enabled, permission, generation, auth, port, discovery);
  }

  @Test
  public void disabledAndPermissionStatesNeverPublishEvenWhenOldListenerWasBound() {
    var disabled = capture(false, true, 7, true, 3082, ADDRESSES);
    assertEquals(LanAccessPresentation.State.DISABLED, disabled.state);
    assertTrue(disabled.services.isEmpty());
    var denied = capture(true, false, 7, true, 3082, ADDRESSES);
    assertEquals(LanAccessPresentation.State.PERMISSION_REQUIRED, denied.state);
    assertTrue(denied.services.isEmpty());
  }

  @Test
  public void currentGenerationAuthenticationAndHeldListenerAreAllRequired() {
    assertEquals(
        LanAccessPresentation.State.WAITING_AUTH,
        capture(true, true, 0, true, 3082, ADDRESSES).state);
    assertEquals(
        LanAccessPresentation.State.WAITING_AUTH,
        capture(true, true, 7, false, 3082, ADDRESSES).state);
    for (int port : new int[] {0, -1, 65536}) {
      var waiting = capture(true, true, 7, true, port, ADDRESSES);
      assertEquals(LanAccessPresentation.State.LISTENER_UNAVAILABLE, waiting.state);
      assertTrue(waiting.services.isEmpty());
    }
  }

  @Test
  public void actualDynamicPortIsUsedForEveryCurrentInterfaceAddress() {
    var ready = capture(true, true, 7, true, 49231, ADDRESSES);
    assertEquals(LanAccessPresentation.State.READY, ready.state);
    assertEquals(List.of("http://192.168.1.2:49231/", "http://10.2.0.2:49231/"), ready.services);
    assertTrue(ready.stillOffers(7, "http://192.168.1.2:49231/"));
    assertFalse(ready.stillOffers(8, "http://192.168.1.2:49231/"));
    assertFalse(ready.stillOffers(7, "http://192.168.1.2:3082/"));
  }

  @Test
  public void failureAndMissingAddressStatesRemainDistinct() {
    assertEquals(
        LanAccessPresentation.State.DISCOVERY_FAILED,
        capture(true, true, 7, true, 3082, null).state);
    assertEquals(
        LanAccessPresentation.State.DISCOVERY_FAILED,
        capture(
                true,
                true,
                7,
                true,
                3082,
                new LanAddressPolicy.Discovery(
                    List.of(), false, LanAddressPolicy.Failure.INTERFACES))
            .state);
    assertEquals(
        LanAccessPresentation.State.DISCOVERY_DENIED,
        capture(
                true,
                true,
                7,
                true,
                3082,
                new LanAddressPolicy.Discovery(
                    List.of(), false, LanAddressPolicy.Failure.PERMISSION))
            .state);
    assertEquals(
        LanAccessPresentation.State.NO_ADDRESS,
        capture(
                true,
                true,
                7,
                true,
                3082,
                new LanAddressPolicy.Discovery(List.of(), false, LanAddressPolicy.Failure.NONE))
            .state);
    var ipv6 =
        capture(
            true,
            true,
            7,
            true,
            3082,
            new LanAddressPolicy.Discovery(List.of(), true, LanAddressPolicy.Failure.NONE));
    assertEquals(LanAccessPresentation.State.IPV6_ONLY, ipv6.state);
    assertTrue(ipv6.services.isEmpty());
  }

  @Test
  public void discoveryCannotInjectHostnamePortTokenPublicOrIpv6Urls() {
    var snapshot =
        capture(
            true,
            true,
            7,
            true,
            49231,
            new LanAddressPolicy.Discovery(
                List.of(
                    "evil.invalid",
                    "192.168.1.2:80",
                    "192.168.1.2/?token=secret",
                    "127.0.0.1",
                    "172.32.0.2",
                    "2001:db8::2",
                    "192.168.1.2",
                    "192.168.1.2"),
                false,
                LanAddressPolicy.Failure.NONE));
    assertEquals(List.of("http://192.168.1.2:49231/"), snapshot.services);
    assertFalse(snapshot.services.toString().contains("token"));
  }

  @Test
  public void changedSettingsAddressOrListenerRevokesPreviouslyOfferedLink() {
    String old = "http://192.168.1.2:3082/";
    assertFalse(capture(false, true, 7, true, 3082, ADDRESSES).stillOffers(7, old));
    assertFalse(capture(true, false, 7, true, 3082, ADDRESSES).stillOffers(7, old));
    assertFalse(capture(true, true, 7, true, 0, ADDRESSES).stillOffers(7, old));
    assertFalse(
        capture(
                true,
                true,
                7,
                true,
                3082,
                new LanAddressPolicy.Discovery(
                    List.of("192.168.2.2"), false, LanAddressPolicy.Failure.NONE))
            .stillOffers(7, old));
  }
}
