package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VirtualScreenExitProofPolicyTest {
  private final VirtualScreenCoreIdentity born =
      VirtualScreenCoreIdentity.parse(
          "123:456:2000:11111111-2222-3333-4444-555555555555:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  private ProcStat process(long started, char state) {
    String[] fields = new String[20];
    java.util.Arrays.fill(fields, "0");
    fields[0] = String.valueOf(state);
    fields[1] = "1";
    fields[2] = "123";
    fields[3] = "123";
    fields[19] = String.valueOf(started);
    return ProcStat.parse("123 (app_process weird ) name) " + String.join(" ", fields), 123);
  }

  @Test
  public void sameBirthAliveAndUnreadableUnknown() {
    assertEquals(
        VirtualScreenCoreIdentity.Exit.ALIVE,
        VirtualScreenExitProofPolicy.classify(born, born.boot, process(456, 'S'), 2000, false));
    assertEquals(
        VirtualScreenCoreIdentity.Exit.UNKNOWN,
        VirtualScreenExitProofPolicy.classify(born, born.boot, null, 2000, false));
    assertEquals(
        VirtualScreenCoreIdentity.Exit.UNKNOWN,
        VirtualScreenExitProofPolicy.classify(born, born.boot, process(456, 'S'), null, false));
  }

  @Test
  public void recycledPidZombieAndKernelAbsenceProveFormerBirthEnded() {
    assertEquals(
        VirtualScreenCoreIdentity.Exit.EXITED,
        VirtualScreenExitProofPolicy.classify(born, born.boot, process(457, 'S'), 2000, false));
    assertEquals(
        VirtualScreenCoreIdentity.Exit.EXITED,
        VirtualScreenExitProofPolicy.classify(born, born.boot, process(456, 'Z'), 2000, false));
    assertEquals(
        VirtualScreenCoreIdentity.Exit.EXITED,
        VirtualScreenExitProofPolicy.classify(born, born.boot, null, null, true));
  }

  @Test
  public void realDifferentBootProvesExitButMissingBootNeverDoes() {
    assertEquals(
        VirtualScreenCoreIdentity.Exit.EXITED,
        VirtualScreenExitProofPolicy.classify(
            born, "22222222-2222-3333-4444-555555555555", null, null, false));
    assertEquals(
        VirtualScreenCoreIdentity.Exit.UNKNOWN,
        VirtualScreenExitProofPolicy.classify(born, "", null, null, true));
  }
}
