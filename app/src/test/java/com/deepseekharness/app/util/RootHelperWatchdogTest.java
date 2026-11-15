package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.RootHelperWatchdog.Owner;
import com.deepseekharness.app.util.RootHelperWatchdog.Verdict;
import org.junit.Test;

public class RootHelperWatchdogTest {
  private static String stat(int pid, char state, long started) {
    StringBuilder fields = new StringBuilder();
    fields.append(state).append(" 1 ").append(pid).append(' ').append(pid);
    for (int i = 4; i < 19; i++) fields.append(" 0");
    fields.append(' ').append(started).append(" 0 0");
    return pid + " (io.github.sunsetrne.dsha) " + fields;
  }

  private final Owner owner = new Owner(4321, 987654);

  @Test
  public void continuesWhileOwnerIsTheSameProcess() {
    assertEquals(
        Verdict.CONTINUE, RootHelperWatchdog.check(1_000, 70_000, owner, stat(4321, 'S', 987654)));
  }

  @Test
  public void deadlineEndsTheHelper() {
    assertEquals(
        Verdict.DEADLINE, RootHelperWatchdog.check(70_000, 70_000, owner, stat(4321, 'S', 987654)));
  }

  @Test
  public void ownerExitOrPidReuseEndsTheHelper() {
    assertEquals(Verdict.OWNER_GONE, RootHelperWatchdog.check(1_000, 70_000, owner, null));
    assertEquals(
        "覆盖安装后 PID 被新进程复用",
        Verdict.OWNER_GONE,
        RootHelperWatchdog.check(1_000, 70_000, owner, stat(4321, 'S', 111)));
    assertEquals(
        Verdict.OWNER_GONE,
        RootHelperWatchdog.check(1_000, 70_000, owner, stat(4321, 'Z', 987654)));
  }

  @Test
  public void unparsableStatDoesNotKillAWaitingRequest() {
    assertEquals(Verdict.CONTINUE, RootHelperWatchdog.check(1_000, 70_000, owner, "garbage"));
  }

  @Test
  public void unknownOwnerFallsBackToDeadlineOnly() {
    Owner legacy = new Owner(-1, -1);
    assertFalse(legacy.known());
    assertEquals(Verdict.CONTINUE, RootHelperWatchdog.check(1_000, 90_000, legacy, null));
    assertEquals(Verdict.DEADLINE, RootHelperWatchdog.check(90_000, 90_000, legacy, null));
    assertEquals(Verdict.CONTINUE, RootHelperWatchdog.check(1_000, 90_000, null, null));
  }

  @Test
  public void requestedDeadlineIsBounded() {
    assertEquals(70_000, RootHelperWatchdog.deadlineMs(70_000, 90_000));
    assertEquals(90_000, RootHelperWatchdog.deadlineMs(-1, 90_000));
    assertEquals(90_000, RootHelperWatchdog.deadlineMs(0, 90_000));
    assertEquals(
        90_000, RootHelperWatchdog.deadlineMs(RootHelperWatchdog.MAX_DEADLINE_MS + 1, 90_000));
    assertEquals(
        RootHelperWatchdog.MAX_DEADLINE_MS,
        RootHelperWatchdog.deadlineMs(RootHelperWatchdog.MAX_DEADLINE_MS, 90_000));
  }
}
