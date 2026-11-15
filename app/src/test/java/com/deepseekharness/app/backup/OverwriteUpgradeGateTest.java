package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.DshVersionPolicy;
import com.deepseekharness.app.util.EnvironmentIdentity;
import com.deepseekharness.app.util.MaintenanceFooterPolicy;
import com.deepseekharness.app.util.RuntimeUpgradeState;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * 覆盖安装门禁的三份真实描述符回归：build322 / build323 / build324。
 *
 * <p>只覆盖判定层（描述符最新性、数据代次、入口门禁、维护页底栏与受限入口），不启动任何 Android
 * 组件。夹具沿用真实发布描述符的契约字段（baseVersion 10 / DSHA_ARM64_V2 / bridgeProtocol 3 /
 * dataRead / dataWrite / dshVersion）；{@code inputs} 用等价的合成摘要，只表达“受管输入变了”。
 *
 * <p>关键回归：build323 → build324 是**同一 dsh 版本、仅受管补丁与描述符变化**的覆盖安装，
 * {@code compatible()} 恒为 true（可以继续运行旧树），但 {@code latest()} 为 false，所以它必须
 * 判定为“需要维护”，维护页也不能把“进入主界面”当成主操作。
 */
public class OverwriteUpgradeGateTest {
  private static final String LAUNCHER = "DSHA_ARM64_V2";
  private static final String BASE = "10";
  private static final long BRIDGE = 3L;

  /** build322：内置 DSH 0.2.1-rc.5。 */
  private static RuntimeDescriptor build322() throws IOException {
    return descriptor(
        "0.2.1-rc.5", List.of("dsh-0.2.1-rc.3", "dsh-0.2.1-rc.5"), "dsh-0.2.1-rc.5", "a");
  }

  /** build323：内置 DSH 0.2.2-rc.1。 */
  private static RuntimeDescriptor build323() throws IOException {
    return descriptor(
        "0.2.2-rc.1", List.of("dsh-0.2.1-rc.5", "dsh-0.2.2-rc.1"), "dsh-0.2.2-rc.1", "b");
  }

  /** build324：内置 DSH 0.2.2-rc.1，仅受管补丁与描述符输入变化。 */
  private static RuntimeDescriptor build324() throws IOException {
    return descriptor(
        "0.2.2-rc.1", List.of("dsh-0.2.1-rc.5", "dsh-0.2.2-rc.1"), "dsh-0.2.2-rc.1", "c");
  }

  private static RuntimeDescriptor descriptor(
      String dshVersion, List<String> dataRead, String dataWrite, String revision)
      throws IOException {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("version", 1L);
    value.put("baseVersion", BASE);
    value.put("dshVersion", dshVersion);
    value.put("launcherContract", LAUNCHER);
    value.put("bridgeProtocol", BRIDGE);
    value.put("dataRead", dataRead);
    value.put("dataWrite", dataWrite);
    value.put(
        "inputs",
        Map.of(
            "runtime", revision.repeat(64),
            "dsh-runtime.bin", revision.repeat(32),
            "first-connect-indicator-patch.json", revision.repeat(16)));
    value.put("runtimeId", RuntimeDescriptor.calculateId(value));
    return new RuntimeDescriptor(value);
  }

  /** 与 EnvironmentAccess.requiresMaintenanceBeforeStart 的候选尝试键一致。 */
  private static String attemptKey(RuntimeDescriptor expected, long apkUpdatedAt) {
    return EnvironmentIdentity.attemptKey("runtime:" + expected.id(), apkUpdatedAt);
  }

  @Test
  public void dshVersionBumpEntersMaintenance() throws Exception {
    RuntimeDescriptor installed = build322(), expected = build323();
    assertNotEquals(installed.id(), expected.id());
    assertTrue(installed.compatible(expected));
    assertFalse(installed.latest(expected));
    assertEquals(
        DshVersionPolicy.Status.INSTALLED_OLDER,
        DshVersionPolicy.assess(
            installed.dshVersion(),
            installed.dataWrite(),
            expected.dshVersion(),
            expected.dataRead()));
    assertTrue(
        EnvironmentIdentity.shouldBlockRuntimeStart(
            false, true, false, false, attemptKey(expected, 1_000L), ""));
  }

  @Test
  public void managedPatchOverwriteOfTheSameDshVersionEntersMaintenance() throws Exception {
    RuntimeDescriptor installed = build323(), expected = build324();
    // 同一 dsh 版本，只有受管输入与描述符身份变化：仍可运行旧树，但它不是当前候选。
    assertEquals("0.2.2-rc.1", installed.dshVersion());
    assertEquals(expected.dshVersion(), installed.dshVersion());
    assertNotEquals(installed.id(), expected.id());
    assertTrue(installed.compatible(expected));
    assertFalse(installed.latest(expected));
    // 数据代次相同 → 不是 INSTALLED_NEWER，允许自动原位更新。
    assertEquals(
        DshVersionPolicy.Status.CURRENT,
        DshVersionPolicy.assess(
            installed.dshVersion(),
            installed.dataWrite(),
            expected.dshVersion(),
            expected.dataRead()));
    // 入口门禁（主界面与 Web 启动共用）必须要求维护。
    String candidate = attemptKey(expected, 2_000L);
    assertTrue(
        EnvironmentIdentity.shouldBlockRuntimeStart(false, true, false, false, candidate, ""));
    // 同一候选只自动尝试一次：新候选第一次必须自动开始。
    assertTrue(
        EnvironmentIdentity.shouldAutoStart(
            false, false, false, candidate, "", BackupTaskState.Status.SUCCEEDED, true, false));
    // 未确认原实例时不自动开始（安全不变式），但门禁仍要求维护。
    assertFalse(
        EnvironmentIdentity.shouldAutoStart(
            false, false, false, candidate, "", BackupTaskState.Status.SUCCEEDED, true, true));
    assertTrue(
        EnvironmentIdentity.shouldBlockRuntimeStart(false, true, true, false, candidate, ""));
    // 环境可继续运行也仍算“需要维护”：主界面的环境横幅据此留出回到维护页的入口。
    assertTrue(RuntimeUpgradeState.maintenanceRequired(true, installed.latest(expected)));
  }

  @Test
  public void uiOnlyApkUpdateWithUnchangedManagedInputsNeverNeedsMaintenance() throws Exception {
    // UI-only 覆盖：描述符身份完全不变（APK 版本码不进入 runtimeId）。
    RuntimeDescriptor installed = build324(), expected = build324();
    assertTrue(installed.latest(expected));
    assertTrue(installed.compatible(expected));
    assertEquals(
        DshVersionPolicy.Status.CURRENT,
        DshVersionPolicy.assess(
            installed.dshVersion(),
            installed.dataWrite(),
            expected.dshVersion(),
            expected.dataRead()));
    assertFalse(
        EnvironmentIdentity.shouldBlockRuntimeStart(
            true, true, false, false, attemptKey(expected, 3_000L), ""));
    // 已是最新时“无事可做”，主界面才是主操作，也不需要受限入口。
    assertTrue(RuntimeUpgradeState.upToDate(true, installed.latest(expected)));
    assertFalse(RuntimeUpgradeState.limitedEntry(true, installed.latest(expected), false, false));
  }

  /**
   * 包内描述符的回归不绑定具体 build 号：直接从当前 {@code runtime-descriptor.json} 克隆一份“同 dsh
   * 版本、只换受管输入”的前代，验证 323→324 这类补丁级覆盖一定被判为需要维护并允许自动开始。
   * build325/326 重算描述符后本测试仍然成立。
   */
  @Test
  public void shippedDescriptorRequiresMaintenanceForAPatchLevelPredecessor() throws Exception {
    RuntimeDescriptor shipped = shippedDescriptor();
    RuntimeDescriptor previous = patchLevelPredecessor(shipped);
    assertEquals(shipped.dshVersion(), previous.dshVersion());
    assertEquals(shipped.dataWrite(), previous.dataWrite());
    assertEquals(shipped.dataRead(), previous.dataRead());
    assertNotEquals(shipped.id(), previous.id());
    // 旧树还能跑（兼容），但它不是当前候选：必须进入维护并允许自动开始。
    assertTrue(previous.compatible(shipped));
    assertFalse(previous.latest(shipped));
    assertFalse(RuntimeUpgradeState.upToDate(true, previous.latest(shipped)));
    assertTrue(
        EnvironmentIdentity.shouldBlockRuntimeStart(
            false, true, false, false, attemptKey(shipped, 4_000L), ""));
    assertTrue(
        EnvironmentIdentity.shouldAutoStart(
            false,
            false,
            false,
            attemptKey(shipped, 4_000L),
            "",
            BackupTaskState.Status.SUCCEEDED,
            true,
            false));
  }

  /** 本仓库真实生成的 runtime-descriptor.json（单元测试工作目录是 app 模块）。 */
  private static RuntimeDescriptor shippedDescriptor() throws Exception {
    java.io.File file = new java.io.File("src/main/assets/runtime-descriptor.json");
    if (!file.isFile()) file = new java.io.File("app/src/main/assets/runtime-descriptor.json");
    assertTrue(file.isFile());
    return new RuntimeDescriptor(
        BackupJson.read(java.nio.file.Files.readAllBytes(file.toPath()), BackupLimits.MANIFEST));
  }

  /** 只改受管输入（不碰 dshVersion / dataRead / dataWrite / launcherContract / bridgeProtocol）。 */
  private static RuntimeDescriptor patchLevelPredecessor(RuntimeDescriptor shipped)
      throws Exception {
    Map<String, Object> value = new LinkedHashMap<>(shipped.json());
    @SuppressWarnings("unchecked")
    Map<String, Object> inputs = new LinkedHashMap<>((Map<String, Object>) value.get("inputs"));
    inputs.put("predecessor-managed-tree", "e".repeat(64));
    value.put("inputs", inputs);
    value.put("runtimeId", RuntimeDescriptor.calculateId(value));
    return new RuntimeDescriptor(value);
  }

  @Test
  public void unresolvedOldWebStillOpensMaintenancePageButNeverAutoStarts() throws Exception {
    RuntimeDescriptor installed = build323(), expected = build324();
    String candidate = attemptKey(expected, 5_000L);
    // 覆盖安装后旧 Web 仍存活时 hasUnresolvedWebSession() 为 true：环境仍需维护，页面必须打开。
    boolean maintenance =
        EnvironmentIdentity.shouldBlockRuntimeStart(false, true, false, false, candidate, "");
    assertTrue(RuntimeUpgradeState.openMaintenancePage(false, maintenance, false));
    // 但自动开始必须被安全门禁拦住，等用户在维护页手点更新。
    assertFalse(
        EnvironmentIdentity.shouldAutoStart(
            false, false, false, candidate, "", BackupTaskState.Status.SUCCEEDED, true, true));
    // 没有未确认原实例时仍按“同一候选只自动尝试一次”自动开始。
    assertTrue(
        EnvironmentIdentity.shouldAutoStart(
            false, false, false, candidate, "", BackupTaskState.Status.SUCCEEDED, true, false));
    // 已是最新候选时不需要维护页。
    // 已是最新候选：门禁不要求维护，维护页也就不该打开。
    assertFalse(
        RuntimeUpgradeState.openMaintenancePage(false, !installed.latest(installed), false));
  }

  @Test
  public void maintenancePageMustNotPromoteEntryWhileManagedPatchIsPending() throws Exception {
    RuntimeDescriptor installed = build323(), expected = build324();
    // 旧判定用 isEnvironmentReady()（只看 compatible）→ 误判“就绪”，把“进入主界面”升为主操作。
    assertTrue(installed.compatible(expected));
    var stale = MaintenanceFooterPolicy.decide(false, false, false, false, true);
    assertEquals(MaintenanceFooterPolicy.Primary.DEMOTED, stale.primary);
    assertEquals(MaintenanceFooterPolicy.Entry.PRIMARY, stale.entry);
    // 维护页的“就绪”必须是“环境可继续运行 **且** 已装运行时就是当前候选”：
    // 只有 compatible 时按 ready 渲染会把“进入主界面”升为主操作、把更新降为次要按钮。
    boolean ready = RuntimeUpgradeState.upToDate(true, installed.latest(expected));
    assertFalse(ready);
    var fixed = MaintenanceFooterPolicy.decide(false, false, false, false, ready);
    assertEquals(MaintenanceFooterPolicy.Primary.NORMAL, fixed.primary);
    assertEquals(MaintenanceFooterPolicy.Entry.OUTLINE, fixed.entry);
  }

  @Test
  public void pendingManagedPatchMakesEntryLimited() throws Exception {
    RuntimeDescriptor installed = build323(), expected = build324();
    // compatible 为 true 只说明旧树还能跑；补丁级更新没做完时进主界面必须记录受限身份，
    // 否则主界面门禁（requiresMaintenanceBeforeStart 仍为 true）会立刻把用户送回维护页。
    assertTrue(installed.compatible(expected));
    assertFalse(installed.latest(expected));
    assertTrue(RuntimeUpgradeState.limitedEntry(true, installed.latest(expected), false, false));
    RuntimeDescriptor current = build324();
    assertFalse(RuntimeUpgradeState.limitedEntry(true, current.latest(current), false, false));
  }
}
