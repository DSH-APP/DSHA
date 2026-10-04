package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProcStatTest {
  private static String sample(int parent, int group, int session, long born, char state) {
    return "321 (node (comm) with spaces)) "
        + state
        + " "
        + parent
        + " "
        + group
        + " "
        + session
        + " 0".repeat(15)
        + " "
        + born
        + " 0 0\n";
  }

  @Test
  public void commonValuesPreserveNestedCommAndDistinctCallerOwnership() {
    String input = sample(12, 321, 321, 9988, 'S');
    ProcStat stat = ProcStat.parse(input, 321);
    assertNotNull(stat);
    assertEquals(12, stat.parent);
    assertEquals(321, stat.group);
    assertEquals(321, stat.session);
    assertEquals(9988, stat.started);
    assertNotNull(WebPidIdentity.parse(input, 321));
    assertNotNull(ProcessIdentity.fromStat(input, 321, 12));
    assertNull(ProcessIdentity.fromStat(input, 321, 99));
    assertNotNull(ProcessIdentity.inSession(input, 321, 321));
    assertNull(ProcessIdentity.inSession(input, 321, 322));
  }

  @Test
  public void kernelParentZeroIsAValueButNotAnOwnedAndroidChild() {
    String input = sample(0, 0, 0, 100, 'S');
    assertNotNull(ProcStat.parse(input, 321));
    assertNotNull(WebPidIdentity.parse(input, 321));
    assertNull(ProcessIdentity.fromStat(input, 321, 12));
    assertNull(ProcessIdentity.inSession(input, 321, 12));
  }

  @Test
  public void malformedDeniedTruncatedAndReusedPidRemainUnknown() {
    String input = sample(12, 321, 321, 900, 'S');
    for (String bad :
        new String[] {
          null,
          "EPERM",
          "321 (node) S 12",
          sample(12, 321, 321, 0, 'S'),
          input.replace("S 12 321", "S invalid 321"),
          "x".repeat(8193)
        }) {
      assertNull(ProcStat.parse(bad, 321));
      assertNull(WebPidIdentity.parse(bad, 321));
      assertNull(ProcessIdentity.fromStat(bad, 321, 12));
    }
    assertNull(ProcStat.parse(input, 322));
    assertFalse(
        WebPidIdentity.parse(input, 321)
            .sameProcess(WebPidIdentity.parse(sample(12, 321, 321, 901, 'S'), 321)));
    assertFalse(
        ProcessIdentity.fromStat(input, 321, 12)
            .sameProcess(ProcessIdentity.fromStat(sample(12, 321, 321, 901, 'S'), 321, 12)));
  }

  @Test
  public void commonExitStateDoesNotExpandSignalPolicy() {
    for (char state : new char[] {'Z', 'X', 'x'}) {
      assertTrue(ProcStat.parse(sample(12, 321, 321, 100, state), 321).exited());
      assertTrue(WebPidIdentity.parse(sample(12, 321, 321, 100, state), 321).exited());
      assertTrue(ProcessIdentity.fromStat(sample(12, 321, 321, 100, state), 321, 12).exited());
    }
    assertFalse(ProcStat.parse(sample(12, 321, 321, 100, 'S'), 321).exited());
    assertFalse(ProcessIdentity.inSession(sample(12, 444, 321, 100, 'S'), 321, 321).ownsSession());
    assertTrue(ProcessIdentity.fromStat(sample(12, 321, 321, 100, 'S'), 321, 12).ownsSession());
  }
}
