package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.Set;
import org.junit.Test;

/** 环境重建目录归属契约：与历史隐式集合逐项一致，并锁定 /opt 与受管路径的优先级。 */
public class MaintenanceToolPathsTest {
  private static final Set<String> HISTORICAL_SYSTEM =
      Set.of(
          "bin",
          "sbin",
          "lib",
          "lib64",
          "usr",
          "etc",
          "var",
          "tmp",
          "run",
          "proc",
          "dev",
          "sys",
          "root",
          ".l2s",
          "lost+found",
          "sdcard",
          "storage",
          "system",
          "apex");

  @Test
  public void systemRootsStayExactlyTheHistoricalSet() {
    assertEquals(HISTORICAL_SYSTEM, MaintenanceToolPaths.SYSTEM_ROOTS);
  }

  @Test
  public void optIsAUserToolRootCopiedByRebuild() {
    assertEquals(MaintenanceToolPaths.Kind.USER_TOOL, MaintenanceToolPaths.classify("opt"));
    assertEquals(
        MaintenanceToolPaths.Kind.USER_TOOL,
        MaintenanceToolPaths.classify("opt/android-sdk/platform-tools/adb"));
    assertTrue(MaintenanceToolPaths.preservedByRebuild("opt"));
    assertTrue(
        MaintenanceToolPaths.preservedByRebuild("opt/gh")); // managed binary is finer-grained
  }

  @Test
  public void managedPathsWinOverEveryTopLevelRule() {
    for (String path :
        new String[] {
          "opt/gh/bin/gh",
          "root/dsh-bin/gh",
          "root/dsh-bin/dsh",
          "usr/local/share/dsha/gh-proxy.mjs",
          "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json"
        }) {
      assertEquals(path, MaintenanceToolPaths.Kind.MANAGED, MaintenanceToolPaths.classify(path));
      assertFalse(path, MaintenanceToolPaths.preservedByRebuild(path));
    }
  }

  @Test
  public void userFilesUnderUsrLocalAreNotPreservedByRebuild() {
    for (String path : new String[] {"usr/local/bin/gh", "usr/local/share/my-tool/run.sh"}) {
      assertEquals(path, MaintenanceToolPaths.Kind.SYSTEM, MaintenanceToolPaths.classify(path));
      assertFalse(path, MaintenanceToolPaths.preservedByRebuild(path));
    }
  }

  @Test
  public void rootChildrenAreDataWhileRootItselfIsSystemTree() {
    assertEquals(MaintenanceToolPaths.Kind.DATA, MaintenanceToolPaths.classify("root/project/a"));
    assertEquals(
        MaintenanceToolPaths.Kind.DATA, MaintenanceToolPaths.classify("root/.dsh/sessions/a"));
    assertTrue(MaintenanceToolPaths.preservedByRebuild("root/project/a"));
    assertTrue(MaintenanceToolPaths.system("root"));
  }

  @Test
  public void otherTopLevelDataAndSystemTrees() {
    for (String path : new String[] {"mnt/disk", "home/user", "srv/data", "data/app"})
      assertEquals(path, MaintenanceToolPaths.Kind.DATA, MaintenanceToolPaths.classify(path));
    for (String path : new String[] {"etc/hosts", "var/lib/x", "usr/bin/curl"})
      assertEquals(path, MaintenanceToolPaths.Kind.SYSTEM, MaintenanceToolPaths.classify(path));
    assertFalse(MaintenanceToolPaths.managed("opt/user-tool/bin/tool"));
    assertFalse(MaintenanceToolPaths.userTool("usr/local/bin/gh"));
  }
}
