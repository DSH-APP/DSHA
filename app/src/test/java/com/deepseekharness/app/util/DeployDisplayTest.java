package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.BackupTaskState.Status;
import com.deepseekharness.app.util.DeployStages.Phase;
import org.junit.After;
import org.junit.Test;

/** 部署胶囊显示判定：只显示环境维护任务、不重放历史成功、成功后约 5 秒收起。 */
public class DeployDisplayTest {
  private static final String UPDATE = BackupTaskKinds.UPDATE;
  private static final String REBUILD = BackupTaskKinds.REBUILD;
  private static final String BACKUP = BackupTaskKinds.BACKUP;
  private static final String RESTORE = BackupTaskKinds.RESTORE;

  @After
  public void resetLanguage() {
    UiText.setLanguage("zh");
  }

  @Test
  public void runningEnvironmentTasksShowAndOtherTasksStayHidden() {
    assertEquals(
        Phase.RUNNING, DeployDisplay.phase(Status.RUNNING, UPDATE, true, false, false, false));
    assertEquals(
        Phase.RUNNING, DeployDisplay.phase(Status.RUNNING, REBUILD, true, false, true, true));
    assertEquals(
        Phase.RUNNING,
        DeployDisplay.phase(Status.RUNNING, BackupTaskKinds.RECOVER, true, true, false, false));
    // 备份 / 恢复备份沿用原有入口（横幅），胶囊不显示。
    assertEquals(Phase.IDLE, DeployDisplay.phase(Status.RUNNING, BACKUP, true, false, true, true));
    assertEquals(Phase.IDLE, DeployDisplay.phase(Status.PREVIEW, RESTORE, true, false, true, true));
    // 旧版本留下的中文种类名同样识别为环境维护。
    assertEquals(
        Phase.RUNNING, DeployDisplay.phase(Status.RUNNING, "更新运行环境", true, false, false, false));
  }

  @Test
  public void pendingTransactionWinsOverAnyFinishedRecord() {
    for (Status status : Status.values()) {
      assertEquals(Phase.PENDING, DeployDisplay.phase(status, UPDATE, false, true, false, false));
      assertEquals(Phase.PENDING, DeployDisplay.phase(status, BACKUP, false, true, true, true));
    }
  }

  @Test
  public void historicalSuccessIsNotReplayedButObservedSuccessIs() {
    assertEquals(
        Phase.IDLE, DeployDisplay.phase(Status.SUCCEEDED, UPDATE, false, false, true, false));
    assertEquals(
        Phase.SUCCEEDED, DeployDisplay.phase(Status.SUCCEEDED, UPDATE, false, false, true, true));
    // 成功文案不是就绪证明：未就绪时总是提示，与是否本进程观察无关。
    assertEquals(
        Phase.UNREADY, DeployDisplay.phase(Status.SUCCEEDED, UPDATE, false, false, false, false));
    assertEquals(
        Phase.UNREADY, DeployDisplay.phase(Status.SUCCEEDED, UPDATE, false, false, false, true));
    assertEquals(
        Phase.IDLE, DeployDisplay.phase(Status.SUCCEEDED, BACKUP, false, false, true, true));
  }

  @Test
  public void failuresStayVisibleUntilReadyOrRetried() {
    assertEquals(
        Phase.FAILED, DeployDisplay.phase(Status.FAILED, UPDATE, false, false, false, false));
    assertEquals(
        Phase.FAILED, DeployDisplay.phase(Status.FAILED, UPDATE, false, false, true, true));
    // 进程重建前的旧失败、环境已就绪（例如回切成功的兼容前代）：不再打扰。
    assertEquals(Phase.IDLE, DeployDisplay.phase(Status.FAILED, UPDATE, false, false, true, false));
    // 中断 / 取消不是可以重放的证据：仍需用户处理。
    assertEquals(
        Phase.FAILED, DeployDisplay.phase(Status.INTERRUPTED, UPDATE, false, false, true, false));
    assertEquals(
        Phase.FAILED, DeployDisplay.phase(Status.CANCELLED, REBUILD, false, false, true, false));
    // 快照仍写着运行但维护锁已释放：按中断显示。
    assertEquals(
        Phase.FAILED, DeployDisplay.phase(Status.RUNNING, UPDATE, false, false, false, true));
    assertEquals(Phase.IDLE, DeployDisplay.phase(Status.FAILED, BACKUP, false, false, false, true));
  }

  @Test
  public void idleOrUnknownRecordsStayHidden() {
    assertEquals(Phase.IDLE, DeployDisplay.phase(Status.IDLE, UPDATE, false, false, false, false));
    assertEquals(Phase.IDLE, DeployDisplay.phase(null, "", false, false, false, false));
    assertEquals(Phase.IDLE, DeployDisplay.phase(Status.FAILED, null, false, false, false, true));
  }

  @Test
  public void successCollapsesAfterAboutFiveSecondsOnly() {
    assertEquals(5_000L, DeployDisplay.COLLAPSE_AFTER_MILLIS);
    assertFalse(DeployDisplay.collapse(Phase.SUCCEEDED, 1_000, 5_999));
    assertTrue(DeployDisplay.collapse(Phase.SUCCEEDED, 1_000, 6_000));
    assertFalse(DeployDisplay.collapse(Phase.SUCCEEDED, 0, 60_000));
    for (Phase phase : Phase.values())
      if (phase != Phase.SUCCEEDED) assertFalse(DeployDisplay.collapse(phase, 1, 60_000));
  }

  @Test
  public void renderKeyChangesWithContentAndLanguageOnly() {
    UiText.setLanguage("zh");
    DeployStages.View running =
        DeployStages.view(DeployStages.Mode.INSTALL, Phase.RUNNING, -1, "准备解压", "准备解压", 7);
    DeployStages.View same =
        DeployStages.view(DeployStages.Mode.INSTALL, Phase.RUNNING, -1, "准备解压", "准备解压", 7);
    assertEquals(DeployDisplay.key(running), DeployDisplay.key(same));
    DeployStages.View later =
        DeployStages.view(
            DeployStages.Mode.INSTALL,
            Phase.RUNNING,
            -1,
            "解压 Ubuntu 与 Node",
            "解压 Ubuntu 与 Node",
            7);
    assertNotEquals(DeployDisplay.key(running), DeployDisplay.key(later));
    DeployStages.View failed =
        DeployStages.view(DeployStages.Mode.INSTALL, Phase.FAILED, -1, "准备解压", "准备解压", 7);
    assertNotEquals(DeployDisplay.key(running), DeployDisplay.key(failed));
    String chinese = DeployDisplay.key(running);
    UiText.setLanguage("en");
    assertNotEquals(chinese, DeployDisplay.key(running));
    assertEquals("", DeployDisplay.key(null));
  }
}
