package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class MaintenanceOwnershipTest {
  private static final MaintenanceOwnership.Record PLUGIN =
      new MaintenanceOwnership.Record("plugin-install", "3f0c6c1e-6f86-4f7a-9c8e-000000000001");
  private static final MaintenanceOwnership.Record HOST =
      new MaintenanceOwnership.Record("host-data", "3f0c6c1e-6f86-4f7a-9c8e-000000000002");
  private static final MaintenanceOwnership.Record CONFIG =
      new MaintenanceOwnership.Record("startup-configuration", "startup-config-repair");

  private static final EnumSet<MaintenanceOwnership.Owner> NONE_LIVE =
      EnumSet.noneOf(MaintenanceOwnership.Owner.class);

  @Test
  public void noRecordsNeverBlocks() {
    assertFalse(MaintenanceOwnership.blocksEntry(List.of(), Set.of(), NONE_LIVE));
    assertFalse(MaintenanceOwnership.blocksEntry(null, null, null));
  }

  @Test
  public void baselineRecordIsResidueEvenWhileItsOwnerRuns() {
    // 上一个进程留下的插件日志：本进程又开始了插件任务也不能放行。
    assertTrue(
        MaintenanceOwnership.blocksEntry(
            List.of(PLUGIN), Set.of(PLUGIN.key()), EnumSet.of(MaintenanceOwnership.Owner.PLUGINS)));
  }

  @Test
  public void baselineIsPerDomainSoALaterRecordInTheSameDomainStillBlocks() {
    var later =
        new MaintenanceOwnership.Record("plugin-install", "3f0c6c1e-6f86-4f7a-9c8e-000000000009");
    assertEquals(PLUGIN.key(), later.key());
    assertTrue(
        MaintenanceOwnership.blocksEntry(
            List.of(later), Set.of(PLUGIN.key()), EnumSet.of(MaintenanceOwnership.Owner.PLUGINS)));
  }

  @Test
  public void newRecordWithLiveOwnerIsInProgress() {
    assertFalse(
        MaintenanceOwnership.blocksEntry(
            List.of(PLUGIN), Set.of(), EnumSet.of(MaintenanceOwnership.Owner.PLUGINS)));
    assertFalse(
        MaintenanceOwnership.blocksEntry(
            List.of(HOST), Set.of(), EnumSet.of(MaintenanceOwnership.Owner.MAINTENANCE)));
  }

  @Test
  public void newRecordWhoseOwnerStoppedIsResidue() {
    // 本进程写的记录，但执行方已结束仍未提交：等同中断。
    assertTrue(MaintenanceOwnership.blocksEntry(List.of(PLUGIN), Set.of(), NONE_LIVE));
    // 执行方对不上：插件日志不能由数据维护任务担保。
    assertTrue(
        MaintenanceOwnership.blocksEntry(
            List.of(PLUGIN), Set.of(), EnumSet.of(MaintenanceOwnership.Owner.MAINTENANCE)));
  }

  @Test
  public void domainsWithoutOwnerSignalAlwaysBlock() {
    assertTrue(
        MaintenanceOwnership.blocksEntry(
            List.of(CONFIG), Set.of(), EnumSet.allOf(MaintenanceOwnership.Owner.class)));
    assertEquals(MaintenanceOwnership.Owner.NONE, MaintenanceOwnership.owner("cold-install"));
    assertEquals(MaintenanceOwnership.Owner.NONE, MaintenanceOwnership.owner("legacy-maintenance"));
    assertEquals(MaintenanceOwnership.Owner.NONE, MaintenanceOwnership.owner("bounded-guest"));
    assertEquals(MaintenanceOwnership.Owner.NONE, MaintenanceOwnership.owner(null));
  }

  @Test
  public void oneResidueAmongLiveRecordsStillBlocks() {
    assertTrue(
        MaintenanceOwnership.blocksEntry(
            List.of(PLUGIN, HOST),
            Set.of(HOST.key()),
            EnumSet.allOf(MaintenanceOwnership.Owner.class)));
    assertTrue(
        MaintenanceOwnership.blocksEntry(
            List.of(PLUGIN, CONFIG), Set.of(), EnumSet.allOf(MaintenanceOwnership.Owner.class)));
  }

  @Test
  public void missingBaselineOrOwnersFailsClosed() {
    assertTrue(
        MaintenanceOwnership.blocksEntry(
            List.of(PLUGIN), null, EnumSet.of(MaintenanceOwnership.Owner.PLUGINS)));
    assertTrue(MaintenanceOwnership.blocksEntry(List.of(PLUGIN), Set.of(), null));
  }
}
