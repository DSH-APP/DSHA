package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class RuntimeUpgradeStateTest {
  @Test
  public void compatibleButStaleRuntimeIsNotUpToDate() {
    // 环境兼容可见（旧树还能跑）但仍待受管补丁：维护页不能把它当“无事可做”。
    assertFalse(RuntimeUpgradeState.upToDate(true, false));
    assertTrue(RuntimeUpgradeState.upToDate(true, true));
    assertFalse(RuntimeUpgradeState.upToDate(false, true));
    assertFalse(RuntimeUpgradeState.upToDate(false, false));
  }

  @Test
  public void maintenanceRequiredCoversEnvironmentAndPatchGenerations() {
    assertTrue(RuntimeUpgradeState.maintenanceRequired(false, false));
    assertTrue(RuntimeUpgradeState.maintenanceRequired(false, true));
    // 补丁级覆盖：环境兼容可见，但内置受管补丁尚未落地，主界面仍要留出维护入口。
    assertTrue(RuntimeUpgradeState.maintenanceRequired(true, false));
    assertFalse(RuntimeUpgradeState.maintenanceRequired(true, true));
  }

  @Test
  public void maintenancePageOpensEvenWithAnUnconfirmedOldSession() {
    // 覆盖更新 + 旧 Web 未确认：页面必须能打开（unresolved 只拦自动开始），否则用户看不到修复入口。
    assertTrue(RuntimeUpgradeState.openMaintenancePage(false, true, false));
    assertFalse(RuntimeUpgradeState.openMaintenancePage(true, true, false));
    assertFalse(RuntimeUpgradeState.openMaintenancePage(false, true, true));
    assertFalse(RuntimeUpgradeState.openMaintenancePage(false, false, false));
  }

  @Test
  public void pendingPatchMakesLimitedEntry() {
    // 补丁级未完成必须按受限身份进入主界面，否则主界面门禁会立刻送回维护页。
    assertTrue(RuntimeUpgradeState.limitedEntry(true, false, false, false));
    assertTrue(RuntimeUpgradeState.limitedEntry(false, true, false, false));
    assertTrue(RuntimeUpgradeState.limitedEntry(true, true, true, false));
    assertTrue(RuntimeUpgradeState.limitedEntry(true, true, false, true));
    assertFalse(RuntimeUpgradeState.limitedEntry(true, true, false, false));
  }

  @Test
  public void staleFailureSnapshotClearsOnlyAfterExternalRecoveryIsRunnable() {
    // 外部恢复成功、环境可运行且无待恢复：旧失败快照不再显示，页面回到标准更新入口。
    assertTrue(RuntimeUpgradeState.clearStaleFailure(false, false, true, true, 7, 7));
    // 环境不可运行 / 维护中 / 待恢复 / 不是本页任务 / 快照本就成功：都保留原渲染。
    assertFalse(RuntimeUpgradeState.clearStaleFailure(false, false, false, true, 7, 7));
    assertFalse(RuntimeUpgradeState.clearStaleFailure(true, false, true, true, 7, 7));
    assertFalse(RuntimeUpgradeState.clearStaleFailure(false, true, true, true, 7, 7));
    assertFalse(RuntimeUpgradeState.clearStaleFailure(false, false, true, true, 6, 7));
    assertFalse(RuntimeUpgradeState.clearStaleFailure(false, false, true, false, 7, 7));
  }
}
