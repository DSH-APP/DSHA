package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VirtualScreenHandshakeTest {
  private static final String BIRTH =
      "123:456:2000:11111111-2222-3333-4444-555555555555:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  private static final String PAYLOAD = "1 49152 " + "a".repeat(48) + " " + BIRTH;

  @Test
  public void dynamicPortAndBirthTravelTogether() {
    var value =
        VirtualScreenHandshake.started(
            "ROM notice\nDSHA_VSCREEN_STARTED " + PAYLOAD + "\n[EXIT=0]\n");
    assertNotNull(value);
    assertEquals(49152, value.port);
    assertEquals(123, value.identity.pid);
    assertEquals(PAYLOAD, value.payload());
  }

  @Test
  public void oldMarkerDuplicateOrNonzeroLauncherNeverAuthorize() {
    assertNull(
        VirtualScreenHandshake.started("DSHA_VSCREEN_STARTED " + "a".repeat(48) + "\n[EXIT=0]"));
    assertNull(VirtualScreenHandshake.started("DSHA_VSCREEN_STARTED " + PAYLOAD + "\n[EXIT=1]"));
    assertNull(
        VirtualScreenHandshake.started(
            "DSHA_VSCREEN_STARTED "
                + PAYLOAD
                + "\nDSHA_VSCREEN_STARTED "
                + PAYLOAD
                + "\n[EXIT=0]"));
    assertNull(
        VirtualScreenHandshake.started("prefix DSHA_VSCREEN_STARTED " + PAYLOAD + "\n[EXIT=0]"));
  }

  @Test
  public void invalidProtocolPortSecretAndBirthFailClosed() {
    for (String value :
        new String[] {
          PAYLOAD.replace("1 49152", "2 49152"),
          PAYLOAD.replace("49152", "0"),
          PAYLOAD.replace("49152", "65536"),
          PAYLOAD.replace("123:456", "1:456"),
          PAYLOAD + " extra"
        }) assertThrows(IllegalArgumentException.class, () -> VirtualScreenHandshake.parse(value));
  }

  @Test
  public void exitProofNeedsExactBornIdentityAndSuccessfulProbe() {
    var identity = VirtualScreenCoreIdentity.parse(BIRTH);
    String proof = "DSHA_VSCREEN_EXIT_PROOF 1 " + BIRTH + " EXITED\n[EXIT=0]";
    assertEquals(VirtualScreenCoreIdentity.Exit.EXITED, identity.proof(proof));
    assertEquals(
        VirtualScreenCoreIdentity.Exit.UNKNOWN,
        identity.proof(proof.replace("123:456", "123:457")));
    assertEquals(
        VirtualScreenCoreIdentity.Exit.UNKNOWN,
        identity.proof(proof.replace("[EXIT=0]", "[EXIT=1]")));
    assertEquals(
        VirtualScreenCoreIdentity.Exit.UNKNOWN, identity.proof("connection refused\n[EXIT=0]"));
    assertEquals(
        VirtualScreenCoreIdentity.Exit.ALIVE, identity.proof(proof.replace(" EXITED", " ALIVE")));
    assertEquals(VirtualScreenCoreIdentity.Exit.UNKNOWN, identity.proof(proof + "\n" + proof));
  }

  @Test
  public void cleanupStatesNeverMeanStopped() {
    assertTrue(VirtualScreenLifecycleState.START_UNKNOWN.cleanupPending());
    assertTrue(VirtualScreenLifecycleState.DISCONNECTED.cleanupPending());
    assertTrue(VirtualScreenLifecycleState.CLOSE_UNKNOWN.cleanupPending());
    assertFalse(VirtualScreenLifecycleState.READY.cleanupPending());
    assertFalse(VirtualScreenLifecycleState.STOPPED.cleanupPending());
  }
}
