package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.MaintenanceFooterPolicy.Emphasis;
import com.deepseekharness.app.util.MaintenanceFooterPolicy.Entry;
import com.deepseekharness.app.util.MaintenanceFooterPolicy.Model;
import com.deepseekharness.app.util.MaintenanceFooterPolicy.Primary;
import org.junit.Test;

public class MaintenanceFooterPolicyTest {
  @Test
  public void busyShowsProgressEvenIfOtherFlagsAreStale() {
    Model m = MaintenanceFooterPolicy.decide(true, true, true, true, true);
    assertEquals(Primary.PROGRESS, m.primary);
    assertEquals(Entry.OUTLINE, m.entry);
    assertEquals(Emphasis.NONE, m.emphasis);
  }

  @Test
  public void failedAndPendingPointToRecoveryAndEmergency() {
    for (Model m :
        new Model[] {
          MaintenanceFooterPolicy.decide(false, true, false, false, false),
          MaintenanceFooterPolicy.decide(false, false, true, false, false),
          // 待恢复优先于“已就绪”：恢复前不能把进入主界面当主操作
          MaintenanceFooterPolicy.decide(false, true, false, false, true)
        }) {
      assertEquals(Primary.DANGER, m.primary);
      assertEquals(Entry.OUTLINE, m.entry);
      assertEquals(Emphasis.EMERGENCY, m.emphasis);
    }
  }

  @Test
  public void incompleteRecommendsLimitedEntryAndLogs() {
    Model m = MaintenanceFooterPolicy.decide(false, false, false, true, false);
    assertEquals(Primary.NORMAL, m.primary);
    assertEquals(Entry.ACCENT_OUTLINE, m.entry);
    assertEquals(Emphasis.LOGS, m.emphasis);
  }

  @Test
  public void readyKeepsRebuildReachableButPromotesEntry() {
    Model m = MaintenanceFooterPolicy.decide(false, false, false, false, true);
    assertEquals(Primary.DEMOTED, m.primary);
    assertEquals(Entry.PRIMARY, m.entry);
  }

  @Test
  public void idleNotReadyIsPlain() {
    Model m = MaintenanceFooterPolicy.decide(false, false, false, false, false);
    assertEquals(Primary.NORMAL, m.primary);
    assertEquals(Entry.OUTLINE, m.entry);
    assertEquals(Emphasis.NONE, m.emphasis);
  }

  @Test
  public void rescueExportOfferedOnlyWhenMaintenanceStoppedUnfinished() {
    // 失败 / 待恢复 / 就绪检查未通过：说明文案承诺的「导出可读副本」必须有直达入口
    assertTrue(MaintenanceFooterPolicy.decide(false, true, false, false, false).rescue);
    assertTrue(MaintenanceFooterPolicy.decide(false, false, true, false, false).rescue);
    assertTrue(MaintenanceFooterPolicy.decide(false, false, false, true, false).rescue);
    assertTrue(MaintenanceFooterPolicy.decide(false, true, false, false, true).rescue);
    // 维护执行中树正在移动；就绪或空闲时走常规备份
    assertFalse(MaintenanceFooterPolicy.decide(true, true, true, true, false).rescue);
    assertFalse(MaintenanceFooterPolicy.decide(false, false, false, false, true).rescue);
    assertFalse(MaintenanceFooterPolicy.decide(false, false, false, false, false).rescue);
  }

  @Test
  public void progressLevelIsBoundedAndShowsRunningStep() {
    assertEquals(0, MaintenanceFooterPolicy.progressLevel(0, 0, true));
    assertEquals(1000, MaintenanceFooterPolicy.progressLevel(0, 5, true));
    assertEquals(3000, MaintenanceFooterPolicy.progressLevel(1, 5, true));
    assertEquals(2000, MaintenanceFooterPolicy.progressLevel(1, 5, false));
    assertEquals(10_000, MaintenanceFooterPolicy.progressLevel(5, 5, true));
    assertEquals(10_000, MaintenanceFooterPolicy.progressLevel(9, 5, false));
    assertEquals(0, MaintenanceFooterPolicy.progressLevel(-3, 5, false));
  }

  @Test
  public void layoutSwitches() {
    assertFalse(MaintenanceFooterPolicy.sideBySide(392, 860));
    assertTrue(MaintenanceFooterPolicy.sideBySide(860, 392));
    assertTrue(MaintenanceFooterPolicy.sideBySide(600, 960));
    assertFalse(MaintenanceFooterPolicy.sideBySide(392, 0));
    assertFalse(MaintenanceFooterPolicy.stackedLinks(1.0f, 392));
    assertTrue(MaintenanceFooterPolicy.stackedLinks(1.3f, 392));
    assertTrue(MaintenanceFooterPolicy.stackedLinks(1.0f, 320));
  }
}
