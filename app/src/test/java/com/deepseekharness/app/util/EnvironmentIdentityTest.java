package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class EnvironmentIdentityTest {
  @Test
  public void upgradesEnvironmentAndApkWithoutLoopingOnRotationOrFailure() {
    String identity = EnvironmentIdentity.expected("10", 114, "0.1.5-alpha.1");
    assertFalse(EnvironmentIdentity.matches(identity, "9"));
    assertTrue(EnvironmentIdentity.matches(identity, identity));
    assertFalse(
        EnvironmentIdentity.matches(
            identity, EnvironmentIdentity.expected("10", 113, "0.1.5-alpha.1")));
    assertFalse(
        EnvironmentIdentity.matches(
            identity, EnvironmentIdentity.expected("10", 114, "0.1.2-rc.1")));
    assertTrue(EnvironmentIdentity.shouldAutoStart(false, false, false, identity, ""));
    assertFalse(EnvironmentIdentity.shouldAutoStart(false, false, false, identity, identity));
    assertFalse(EnvironmentIdentity.shouldAutoStart(false, true, false, identity, ""));
    assertFalse(EnvironmentIdentity.shouldAutoStart(false, false, true, identity, ""));
    assertFalse(EnvironmentIdentity.shouldAutoStart(true, false, false, identity, ""));
  }

  @Test
  public void missingOrUnreadableVersionFailsClosed() {
    assertEquals("", EnvironmentIdentity.expected("", 114, "0.1.5-alpha.1"));
    assertEquals("", EnvironmentIdentity.expected("10/other", 114, "0.1.5-alpha.1"));
    assertFalse(EnvironmentIdentity.matches("", ""));
    assertFalse(EnvironmentIdentity.matches(null, null));
    assertFalse(EnvironmentIdentity.shouldAutoStart(false, false, false, "", ""));
  }

  @Test
  public void repairApkCanRetryOldFailureOnceWithoutRebuildingReadyEnvironment() {
    String identity = EnvironmentIdentity.expected("10", 115, "0.1.5-alpha.2");
    String previous = EnvironmentIdentity.attemptKey(identity, 100);
    String patched = EnvironmentIdentity.attemptKey(identity, 200);
    assertTrue(EnvironmentIdentity.shouldAutoStart(false, false, false, patched, previous));
    assertTrue(EnvironmentIdentity.shouldAutoStart(false, false, false, patched, identity));
    assertFalse(EnvironmentIdentity.shouldAutoStart(false, false, false, patched, patched));
    assertFalse(EnvironmentIdentity.shouldAutoStart(true, false, false, patched, previous));
    assertEquals("", EnvironmentIdentity.attemptKey("", 200));
  }

  @Test
  public void newApkCannotAutomaticallyReplayInterruptedOrCancelledTask() {
    String oldAttempt = EnvironmentIdentity.attemptKey("runtime:abc", 100);
    String newAttempt = EnvironmentIdentity.attemptKey("runtime:abc", 200);
    for (var status :
        new BackupTaskState.Status[] {
          BackupTaskState.Status.INTERRUPTED, BackupTaskState.Status.CANCELLED
        })
      assertFalse(
          EnvironmentIdentity.shouldAutoStart(
              false, false, false, newAttempt, oldAttempt, status, true, false));
    assertTrue(
        EnvironmentIdentity.shouldAutoStart(
            false,
            false,
            false,
            newAttempt,
            oldAttempt,
            BackupTaskState.Status.FAILED,
            true,
            false));
    assertFalse(
        EnvironmentIdentity.shouldAutoStart(
            false,
            false,
            false,
            newAttempt,
            oldAttempt,
            BackupTaskState.Status.FAILED,
            false,
            false));
    assertFalse(
        EnvironmentIdentity.shouldAutoStart(
            false,
            false,
            false,
            newAttempt,
            oldAttempt,
            BackupTaskState.Status.FAILED,
            true,
            true));
    assertFalse(
        EnvironmentIdentity.shouldAutoStart(
            false,
            false,
            true,
            newAttempt,
            oldAttempt,
            BackupTaskState.Status.FAILED,
            true,
            false));
    assertFalse(
        EnvironmentIdentity.shouldAutoStart(
            false,
            false,
            false,
            newAttempt,
            newAttempt,
            BackupTaskState.Status.FAILED,
            true,
            false));
    assertFalse(
        EnvironmentIdentity.shouldAutoStart(
            true,
            false,
            false,
            newAttempt,
            oldAttempt,
            BackupTaskState.Status.FAILED,
            true,
            false));
  }

  @Test
  public void successMessageWithoutReadinessCannotNavigateBackIntoMaintenance() {
    assertFalse(
        EnvironmentIdentity.mayAdvanceAfterMaintenance(true, true, true, false, false, false));
    assertFalse(
        EnvironmentIdentity.mayAdvanceAfterMaintenance(true, true, true, false, true, true));
    assertFalse(
        EnvironmentIdentity.mayAdvanceAfterMaintenance(true, true, true, true, false, true));
    assertFalse(
        EnvironmentIdentity.mayAdvanceAfterMaintenance(true, true, false, false, false, true));
    assertTrue(
        EnvironmentIdentity.mayAdvanceAfterMaintenance(true, true, true, false, false, true));
    assertFalse(
        EnvironmentIdentity.mayAdvanceAfterMaintenance(false, true, true, false, false, true));
    String candidate = "runtime:" + "a".repeat(64);
    for (int reopened = 0; reopened < 30; reopened++)
      assertFalse(EnvironmentIdentity.shouldAutoStart(false, false, false, candidate, candidate));
  }

  @Test
  public void busyPluginReadCannotHideUnattemptedRuntimeMaintenance() {
    String candidate = "runtime:" + "b".repeat(64);
    String previous = "runtime:" + "a".repeat(64);
    // 后台插件读取只影响维护执行时机，不改变覆盖安装后必须更新的事实。
    assertFalse(EnvironmentIdentity.shouldAutoStart(false, true, false, candidate, previous));
    assertTrue(
        EnvironmentIdentity.shouldBlockRuntimeStart(false, true, true, false, candidate, previous));
    assertTrue(
        EnvironmentIdentity.shouldBlockRuntimeStart(
            false, true, false, false, candidate, previous));
  }

  @Test
  public void pendingMaintenanceCannotHideNewCandidateButAttemptedFallbackRemainsAvailable() {
    String candidate = "runtime:" + "b".repeat(64);
    assertFalse(EnvironmentIdentity.shouldAutoStart(false, false, true, candidate, ""));
    assertTrue(
        EnvironmentIdentity.shouldBlockRuntimeStart(false, true, false, true, candidate, ""));
    assertTrue(EnvironmentIdentity.shouldBlockRuntimeStart(false, true, true, true, candidate, ""));
    // 已尝试候选的健康兼容前代仍交给独立的任务/退出/维护门禁决定是否可运行。
    assertFalse(
        EnvironmentIdentity.shouldBlockRuntimeStart(false, true, true, true, candidate, candidate));
    assertFalse(EnvironmentIdentity.shouldBlockRuntimeStart(true, true, true, true, candidate, ""));
  }

  @Test
  public void stickyServiceCannotBypassManagedRuntimeUpdate() {
    String candidate = "runtime:" + "b".repeat(64);
    assertTrue(
        EnvironmentIdentity.shouldBlockRuntimeStart(false, true, false, false, candidate, ""));
    assertFalse(
        EnvironmentIdentity.shouldBlockRuntimeStart(
            false, true, false, false, candidate, candidate));
    assertFalse(
        EnvironmentIdentity.shouldBlockRuntimeStart(true, true, false, false, candidate, ""));
    assertTrue(EnvironmentIdentity.shouldBlockRuntimeStart(false, false, false, false, "", ""));
    assertTrue(EnvironmentIdentity.shouldBlockRuntimeStart(false, false, true, true, "", ""));
  }
}
