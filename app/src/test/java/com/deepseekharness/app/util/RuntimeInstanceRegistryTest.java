package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeInstanceRegistryTest {
  private static final String ID = "1234567890abcdef1234567890abcdef";
  private static final String COMMAND =
      "/usr/local/bin/node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --profile dsha-emergency-"
          + ID
          + " --port 0";

  private static WebPidIdentity identity(int pid, long start) {
    return WebPidIdentity.parse(
        pid + " (node) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 " + start, pid);
  }

  @Test
  public void onlyVerifiedBirthAndDirectCommandAreExcluded() {
    RuntimeInstanceRegistry registry = new RuntimeInstanceRegistry();
    WebPidIdentity birth = identity(202, 100);
    assertNotNull(birth);
    assertFalse(registry.isIndependentRecovery(birth, COMMAND));
    registry.registerRecovery(ID, 1, birth, COMMAND, true);
    assertTrue(registry.isIndependentRecovery(birth, COMMAND));
    assertFalse(registry.isIndependentRecovery(null, COMMAND));
    assertFalse(registry.isIndependentRecovery(identity(202, 101), COMMAND));
    assertFalse(registry.isIndependentRecovery(identity(203, 100), COMMAND));
    assertFalse(registry.isIndependentRecovery(birth, "/bin/bash -c " + COMMAND));
    assertFalse(
        registry.isIndependentRecovery(
            birth, COMMAND.replace(ID, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
  }

  @Test
  public void staleCompletionCannotRemoveNewGeneration() {
    RuntimeInstanceRegistry registry = new RuntimeInstanceRegistry();
    registry.registerRecovery(ID, 1, identity(202, 100), COMMAND, true);
    registry.registerRecovery(ID, 2, identity(203, 200), COMMAND, true);
    registry.remove(ID, 1);
    assertTrue(registry.isIndependentRecovery(identity(203, 200), COMMAND));
    registry.remove(ID, 2);
    assertFalse(registry.isIndependentRecovery(identity(203, 200), COMMAND));
  }

  @Test
  public void noRosterProofCannotRegister() {
    RuntimeInstanceRegistry registry = new RuntimeInstanceRegistry();
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.registerRecovery(ID, 1, identity(202, 100), COMMAND, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.registerRecovery("../bad", 1, identity(202, 100), COMMAND, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.registerRecovery(ID, 1, identity(202, 100), "sh -c " + COMMAND, true));
  }

  @Test
  public void repeatedGenerationCannotReplaceBirth() {
    RuntimeInstanceRegistry registry = new RuntimeInstanceRegistry();
    registry.registerRecovery(ID, 2, identity(202, 100), COMMAND, true);
    assertThrows(
        IllegalStateException.class,
        () -> registry.registerRecovery(ID, 2, identity(203, 200), COMMAND, true));
  }

  @Test
  public void unknownEmergencyRemainsAWebProcessForTheStopBarrier() {
    assertTrue(WebProcSel.looksLikeWeb(COMMAND));
    assertTrue(WebProcSel.maySignalWeb(COMMAND));
    assertEquals("", WebProcSel.trialProfile(COMMAND));
    assertTrue(WebStopEvidence.scanUnconfirmed(WebStopEvidence.Kind.WEB, false));
    assertFalse(WebProcSel.maySignalWeb("/bin/bash -c " + COMMAND));
  }
}
