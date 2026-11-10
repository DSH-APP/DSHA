package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

public class DeployStagesTest {
  @After
  public void restoreLanguage() {
    UiText.setLanguage("zh");
  }

  @Test
  public void coldInstallStagesMapInOrderWithoutPercentages() {
    UiText.setLanguage("zh");
    var mode = DeployStages.Mode.INSTALL;
    assertEquals(0, DeployStages.match(mode, "正在准备…"));
    assertEquals(1, DeployStages.match(mode, "解压 Ubuntu 与 Node"));
    assertEquals(2, DeployStages.match(mode, "解压 dsh 与内置依赖"));
    assertEquals(5, DeployStages.match(mode, "安装离线 curl、git 与证书"));
    assertEquals(6, DeployStages.match(mode, "核对完整安装文件…"));
    assertEquals(7, DeployStages.match(mode, "检查启动配置…"));
    assertEquals(7, DeployStages.match(mode, "正在核验原生模块…"));
    assertEquals(-1, DeployStages.match(mode, "完全未知的进度"));
    assertEquals(8, DeployStages.stages(mode).size());
  }

  @Test
  public void englishProgressMatchesTheSameStages() {
    UiText.setLanguage("en");
    var mode = DeployStages.Mode.INSTALL;
    assertEquals(1, DeployStages.match(mode, UiText.text("解压 Ubuntu 与 Node")));
    assertEquals(6, DeployStages.match(mode, "Checking complete installation files…"));
    assertEquals(7, DeployStages.match(mode, "Checking native modules…"));
    var view =
        DeployStages.view(
            mode, DeployStages.Phase.RUNNING, 0, UiText.text("解压 Ubuntu 与 Node"), "", 1);
    assertEquals("Deploying", view.label());
    assertEquals("2 / 8", view.counter());
  }

  @Test
  public void progressTextCorrectsTheDisplayedMode() {
    UiText.setLanguage("zh");
    assertEquals(
        DeployStages.Mode.UPDATE,
        DeployStages.infer(DeployStages.Mode.INSTALL, "正在解压新版 dsh（保留现有 Ubuntu 与个人目录）…"));
    assertEquals(
        DeployStages.Mode.INSTALL,
        DeployStages.infer(DeployStages.Mode.UPDATE, "解压 Ubuntu 与 Node"));
    assertEquals(
        DeployStages.Mode.REBUILD, DeployStages.infer(DeployStages.Mode.UPDATE, "正在解压内置环境… 12 MB"));
    // 一旦确认是重建，不因后续的解压阶段文案回退为首次部署。
    assertEquals(
        DeployStages.Mode.REBUILD,
        DeployStages.infer(DeployStages.Mode.REBUILD, "解压 Ubuntu 与 Node"));
    assertEquals(
        DeployStages.Mode.GENERIC,
        DeployStages.infer(DeployStages.Mode.GENERIC, "解压 Ubuntu 与 Node"));
  }

  @Test
  public void phaseNeverReportsSuccessWithoutReadiness() {
    var ok = BackupTaskState.Status.SUCCEEDED;
    assertEquals(DeployStages.Phase.SUCCEEDED, DeployStages.phase(ok, false, false, true));
    assertEquals(DeployStages.Phase.UNREADY, DeployStages.phase(ok, false, false, false));
    assertEquals(DeployStages.Phase.RUNNING, DeployStages.phase(ok, true, false, false));
    assertEquals(DeployStages.Phase.PENDING, DeployStages.phase(ok, false, true, true));
    assertEquals(
        DeployStages.Phase.FAILED,
        DeployStages.phase(BackupTaskState.Status.INTERRUPTED, false, false, false));
    // 快照写着运行但维护锁已释放：旧进程留下的记录按中断显示。
    assertEquals(
        DeployStages.Phase.FAILED,
        DeployStages.phase(BackupTaskState.Status.RUNNING, false, false, false));
    assertEquals(
        DeployStages.Phase.IDLE,
        DeployStages.phase(BackupTaskState.Status.IDLE, false, false, true));
    assertEquals(DeployStages.Phase.IDLE, DeployStages.phase(null, false, false, true));
  }

  @Test
  public void failedViewMarksTheLastReachedStage() {
    UiText.setLanguage("zh");
    var view =
        DeployStages.view(
            DeployStages.Mode.INSTALL,
            DeployStages.Phase.FAILED,
            0,
            "COLD_INSTALL_SPACE_REQUIRED:1",
            "安装 Python 与 pnpm",
            7);
    assertEquals(3, view.current);
    assertEquals(DeployStages.Step.DONE, view.steps[2]);
    assertEquals(DeployStages.Step.FAILED, view.steps[3]);
    assertEquals(DeployStages.Step.TODO, view.steps[4]);
    assertEquals(3, view.completed());
    assertEquals("部署未完成", view.label());
    assertEquals("", view.counter());
  }

  @Test
  public void reachedStageNeverMovesBackwardsOnUnknownText() {
    var view =
        DeployStages.view(DeployStages.Mode.UPDATE, DeployStages.Phase.RUNNING, 3, "未知文案", "", 1);
    assertEquals(3, view.current);
    assertEquals("切换受管文件".length() > 0, view.sub().length() > 0);
  }

  @Test
  public void successAndUnreadyCompleteEverySegment() {
    var done =
        DeployStages.view(DeployStages.Mode.UPDATE, DeployStages.Phase.SUCCEEDED, 1, "", "", 1);
    for (var step : done.steps) assertEquals(DeployStages.Step.DONE, step);
    assertEquals(done.total(), done.completed());
    var idle = DeployStages.idle();
    assertFalse(idle.visible());
    assertEquals(0, idle.total());
  }

  @Test
  public void onlyEnvironmentMaintenanceKindsAppearInTheCapsule() {
    assertTrue(DeployStages.environmentKind(BackupTaskKinds.UPDATE));
    assertTrue(DeployStages.environmentKind("更新运行环境"));
    assertTrue(DeployStages.environmentKind(BackupTaskKinds.REBUILD));
    assertFalse(DeployStages.environmentKind(BackupTaskKinds.BACKUP));
    assertFalse(DeployStages.environmentKind(BackupTaskKinds.RESTORE));
    assertEquals(DeployStages.Mode.INSTALL, DeployStages.modeFor(BackupTaskKinds.UPDATE, false));
    assertEquals(DeployStages.Mode.UPDATE, DeployStages.modeFor(BackupTaskKinds.UPDATE, true));
    assertEquals(DeployStages.Mode.REBUILD, DeployStages.modeFor(BackupTaskKinds.REBUILD, true));
    assertEquals(DeployStages.Mode.GENERIC, DeployStages.modeFor(BackupTaskKinds.RECOVER, true));
  }
}
