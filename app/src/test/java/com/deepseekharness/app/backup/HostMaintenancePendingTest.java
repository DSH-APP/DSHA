package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Exercises the production read-only domain sequence with synthetic probes only. */
public class HostMaintenancePendingTest {
  private static final File FILES = new File("synthetic-files");

  @Test
  public void productionInventoryHasEightRecoverableDomainsAndExactWeights() {
    assertEquals(
        List.of(
            "cold-install",
            "legacy-maintenance",
            "legacy-runtime",
            "managed-runtime",
            "startup-configuration",
            "plugin-install",
            "environment-rebuild",
            "host-data"),
        HostMaintenancePending.RECOVERABLE.stream().map(domain -> domain.name).toList());
    assertEquals(
        List.of(false, false, false, true, false, false, false, true),
        HostMaintenancePending.RECOVERABLE.stream().map(domain -> domain.countEach).toList());
    assertFalse(
        HostMaintenancePending.RECOVERABLE.stream()
            .anyMatch(domain -> domain.name.equals("bounded-guest")));
  }

  @Test
  public void ordinaryGateReturnsFirstDomainAndDoesNotProbeLaterOnes() {
    AtomicInteger later = new AtomicInteger();
    var domains =
        List.of(
            new HostMaintenancePending.Domain("legacy-runtime", root -> List.of(), false),
            new HostMaintenancePending.Domain(
                "plugin-install", root -> List.of("first", "second"), false),
            new HostMaintenancePending.Domain(
                "host-data",
                root -> {
                  later.incrementAndGet();
                  return List.of("later");
                },
                true));
    var check = HostMaintenancePending.inspectRecoverable(FILES, domains);
    assertTrue(check.blocked);
    assertEquals("plugin-install", check.domain);
    assertEquals("first", check.operation);
    assertEquals(0, later.get());
  }

  @Test
  public void pluginCandidatesCountAsOneButHostAndManagedCountEach() throws Exception {
    var plugin =
        new HostMaintenancePending.Domain("plugin-install", root -> List.of("a", "b", "c"), false);
    assertFalse(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(plugin)));
    var legacy = new HostMaintenancePending.Domain("legacy-runtime", root -> List.of("old"), false);
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(plugin, legacy)));
    var host = new HostMaintenancePending.Domain("host-data", root -> List.of("a", "b"), true);
    var runtime =
        new HostMaintenancePending.Domain("managed-runtime", root -> List.of("a", "b"), true);
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(host)));
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(runtime)));
  }

  @Test
  public void multipleCountStopsAtTwoAndUnreadableStillFailsClosed() throws Exception {
    AtomicInteger later = new AtomicInteger();
    var first = new HostMaintenancePending.Domain("host-data", root -> List.of("a", "b"), true);
    var unreadable =
        new HostMaintenancePending.Domain(
            "startup-configuration",
            root -> {
              later.incrementAndGet();
              throw new IOException("damaged journal");
            },
            false);
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(first, unreadable)));
    assertEquals(0, later.get());
    assertThrows(
        IOException.class,
        () -> HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(unreadable)));
    var display = HostMaintenancePending.inspectRecoverable(FILES, List.of(unreadable));
    assertTrue(display.blocked);
    assertEquals("startup-configuration", display.domain);
    assertEquals("unknown", display.operation);
    assertTrue(display.error.contains("damaged journal"));
  }

  @Test
  public void triStateSeparatesPendingFromUnreadableAndKeepsStableCode() {
    var pending = HostMaintenancePending.Check.pending("host-data", "op");
    assertEquals(HostMaintenancePending.State.PENDING, pending.state);
    assertTrue(pending.blocked);
    assertFalse(pending.unreadable());
    var unreadable =
        HostMaintenancePending.Check.unreadable(
            "plugin-install", new IOException("PLUGIN_JOURNAL_ID"));
    assertEquals(HostMaintenancePending.State.UNREADABLE, unreadable.state);
    assertTrue(unreadable.blocked);
    assertEquals("PLUGIN_JOURNAL_ID", unreadable.code);
    // 非稳定码的原文（可能含路径）不进入 code，只经脱敏后留在 error。
    var free =
        HostMaintenancePending.Check.unreadable("host-data", new IOException("/data/x failed"));
    assertEquals("UNREADABLE", free.code);
    assertFalse(HostMaintenancePending.Check.clear().blocked);
  }

  @Test
  public void inspectAllNeverThrowsAndReportsEveryDomain() {
    var domains =
        List.of(
            new HostMaintenancePending.Domain(
                "plugin-install",
                root -> {
                  throw new IOException("PLUGIN_JOURNAL_ID");
                },
                false),
            new HostMaintenancePending.Domain(
                "startup-configuration",
                root -> {
                  throw new IllegalStateException("probe bug");
                },
                false),
            new HostMaintenancePending.Domain("host-data", root -> List.of("tx"), true),
            new HostMaintenancePending.Domain("legacy-runtime", root -> List.of(), false));
    var all = HostMaintenancePending.inspectAll(FILES, domains);
    assertEquals(3, all.size());
    assertEquals("PLUGIN_JOURNAL_ID", all.get(0).code);
    assertEquals("UNREADABLE_PROBE", all.get(1).code);
    assertEquals(HostMaintenancePending.State.PENDING, all.get(2).state);
  }

  /**
   * 不变式：任何会阻塞启动的域，若探测本身读不了，都必须有一个不依赖该探测成功的出口。
   * 用户反馈的死循环正是「恢复入口依赖同一个失败的扫描」。
   */
  @Test
  public void everyRecoverableDomainHasAnExitThatDoesNotNeedItsOwnProbe() {
    for (var domain : HostMaintenancePending.RECOVERABLE) {
      for (String code : List.of("PLUGIN_JOURNAL_ID", "DATA_LOCATION_AMBIGUOUS", "UNREADABLE")) {
        var check = HostMaintenancePending.Check.unreadable(domain.name, new IOException(code));
        for (boolean isolationAvailable : new boolean[] {false, true}) {
          var exit =
              com.deepseekharness.app.util.MaintenanceExitPolicy.exit(
                  check.domain, check.code, isolationAvailable);
          assertNotNull(domain.name + "/" + code, exit);
          if (com.deepseekharness.app.util.MaintenanceExitPolicy.dataLocation(code))
            assertEquals(
                com.deepseekharness.app.util.MaintenanceExitPolicy.Exit.SELECT_DATA_HOME, exit);
          else if (domain.name.equals("plugin-install") && isolationAvailable)
            assertEquals(
                com.deepseekharness.app.util.MaintenanceExitPolicy.Exit.ISOLATE_PLUGIN_JOURNALS,
                exit);
          else
            // 方案一：其余域只给诊断，绝不提供跳过回滚的隔离。
            assertEquals(com.deepseekharness.app.util.MaintenanceExitPolicy.Exit.DIAGNOSTICS, exit);
        }
      }
    }
  }

  /** 复现第二个死循环：新旧数据根并存时各域读不了，「选择数据目录」不能被同一状态挡住。 */
  @Test
  public void ambiguousDataRootNoLongerBlocksChoosingTheDataHome() throws Exception {
    File files = java.nio.file.Files.createTempDirectory("dsha-layout").toFile();
    try {
      new File(files, UserDataLayout.LEGACY).mkdirs();
      new File(files, UserDataLayout.STABLE).mkdirs();
      var fs = new JvmBackupFileSystem();
      IOException ambiguous =
          assertThrows(IOException.class, () -> new UserDataLayout(fs, files).current());
      assertEquals("DATA_LOCATION_AMBIGUOUS", ambiguous.getMessage());
      var plugin = HostMaintenancePending.Check.unreadable("plugin-install", ambiguous);
      var config = HostMaintenancePending.Check.unreadable("startup-configuration", ambiguous);
      assertNull(HostMaintenancePending.blocksDataHomeSelection(List.of(plugin, config)));
      // 真正的待恢复事务或其他读不了的记录仍须先处理。
      var host = HostMaintenancePending.Check.pending("host-data", "tx");
      assertSame(host, HostMaintenancePending.blocksDataHomeSelection(List.of(plugin, host)));
      var damaged =
          HostMaintenancePending.Check.unreadable(
              "plugin-install", new IOException("PLUGIN_JOURNAL_ID"));
      assertSame(damaged, HostMaintenancePending.blocksDataHomeSelection(List.of(damaged)));
    } finally {
      try (var walk = java.nio.file.Files.walk(files.toPath())) {
        walk.sorted(java.util.Comparator.reverseOrder())
            .map(java.nio.file.Path::toFile)
            .forEach(File::delete);
      }
    }
  }
}
