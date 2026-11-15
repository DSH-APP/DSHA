package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class PermissionExpansionTest {
  @Test
  public void everyCardStartsCollapsed() {
    PermissionExpansion e = new PermissionExpansion();
    for (String card :
        new String[] {
          PermissionGoals.NOTIFY,
          PermissionGoals.BATTERY,
          PermissionGoals.STORAGE,
          PermissionGoals.A11Y,
          PermissionGoals.OVERLAY,
          PermissionGoals.CHANNEL
        }) {
      assertFalse(e.expanded(card, true));
      assertFalse(e.expanded(card, false));
    }
  }

  @Test
  public void toggleIsRememberedAcrossRepeatedRendersWithTheSameState() {
    PermissionExpansion e = new PermissionExpansion();
    assertTrue(e.toggle(PermissionGoals.NOTIFY, true));
    // 每秒轮询重绘：状态没变，展开保持。
    assertTrue(e.expanded(PermissionGoals.NOTIFY, true));
    assertTrue(e.expanded(PermissionGoals.NOTIFY, true));
    // 其他卡不受影响。
    assertFalse(e.expanded(PermissionGoals.BATTERY, true));
    assertFalse(e.toggle(PermissionGoals.NOTIFY, true));
    assertFalse(e.expanded(PermissionGoals.NOTIFY, true));
  }

  @Test
  public void grantingFromSettingsCollapsesTheCardBackToOneLine() {
    PermissionExpansion e = new PermissionExpansion();
    // 未开启时展开查看说明，去系统设置授权后回来：状态变为已满足，回到默认折叠。
    assertTrue(e.toggle(PermissionGoals.STORAGE, false));
    assertFalse(e.expanded(PermissionGoals.STORAGE, true));
    // 在新状态下的选择会清掉旧状态的记忆：之后撤销授权，也不会突然展开。
    e.toggle(PermissionGoals.STORAGE, true);
    assertFalse(e.expanded(PermissionGoals.STORAGE, false));
  }
}
