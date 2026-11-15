package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackupTaskStateTest {
  @org.junit.After
  public void restoreLanguage() {
    UiText.setLanguage("zh");
  }

  @Test
  public void genericMaintenanceProgressKeepsTheLastRecognizedStageOnFailure() {
    for (String language : new String[] {"zh", "en"}) {
      UiText.setLanguage(language);
      BackupTaskState state = new BackupTaskState();
      long id = state.start(BackupTaskKinds.REBUILD);
      String protecting = UiText.choose(
          "正在由 Android 保护应用数据与个人目录；不执行旧环境…",
          "Android is protecting application data and personal folders without executing the old environment…");
      state.update(id, BackupTaskState.Status.RUNNING, protecting);
      state.update(id, BackupTaskState.Status.RUNNING, "正在核验受管文件… 3 MB");
      state.update(id, BackupTaskState.Status.FAILED, "PERMISSION_DENIED");
      var snapshot = state.snapshot();
      assertEquals(protecting, snapshot.lastStage);
      assertEquals("PERMISSION_DENIED", snapshot.detail);
      var view = DeployStages.view(DeployStages.Mode.REBUILD, DeployStages.Phase.FAILED,
          -1, snapshot.detail, snapshot.lastStage, id);
      assertEquals(1, view.current);
      assertEquals(DeployStages.Step.DONE, view.steps[0]);
      assertEquals(DeployStages.Step.FAILED, view.steps[1]);
      assertEquals(DeployStages.Step.TODO, view.steps[2]);
    }
  }

  @Test
  public void restoredInterruptedTaskRetainsItsRecognizedSnapshotStage() {
    UiText.setLanguage("zh");
    BackupTaskState state = new BackupTaskState();
    long id = state.start(BackupTaskKinds.REBUILD);
    String protecting = "正在由 Android 保护应用数据与个人目录；不执行旧环境…";
    state.update(id, BackupTaskState.Status.RUNNING, protecting);
    state.update(id, BackupTaskState.Status.RUNNING, "正在核验受管文件…");
    var saved = state.snapshot();
    BackupTaskState restored = new BackupTaskState();
    restored.restore(saved.id, saved.kind, saved.status, saved.detail);
    restored.restoreStage(saved.lastStage);
    var snapshot = restored.snapshot();
    assertEquals(BackupTaskState.Status.INTERRUPTED, snapshot.status);
    var view = DeployStages.view(DeployStages.Mode.REBUILD, DeployStages.Phase.FAILED,
        -1, snapshot.detail, snapshot.lastStage, snapshot.id);
    assertEquals(1, view.current);
    assertFalse(restored.busy());
  }

  @Test
  public void lateEarlierMaintenanceProgressCannotReplaceALaterStage() {
    UiText.setLanguage("zh");
    BackupTaskState state = new BackupTaskState();
    long id = state.start(BackupTaskKinds.REBUILD);
    String restoring = "正在恢复本机数据并逐文件校验…";
    state.update(id, BackupTaskState.Status.RUNNING, restoring);
    state.update(id, BackupTaskState.Status.RUNNING, "正在停止 Web，等待运行队列完成…");
    assertEquals(restoring, state.snapshot().lastStage);
  }

  @Test
  public void updateTaskRetainsColdInstallStageAcrossUnrecognizedProgress() {
    UiText.setLanguage("zh");
    BackupTaskState state = new BackupTaskState();
    long id = state.start(BackupTaskKinds.UPDATE);
    state.update(id, BackupTaskState.Status.RUNNING, "解压 Ubuntu 与 Node");
    state.update(id, BackupTaskState.Status.RUNNING, "未知的细粒度进度");
    var snapshot = state.snapshot();
    var view = DeployStages.view(DeployStages.Mode.UPDATE, DeployStages.Phase.FAILED,
        -1, "FAILED", snapshot.lastStage, id);
    assertEquals(DeployStages.Mode.INSTALL, view.mode);
    assertEquals(1, view.current);
  }

  @Test
  public void ordinaryBackupStillRecordsEveryProgressMessage() {
    for (String kind : new String[] {BackupTaskKinds.BACKUP, BackupTaskKinds.RECOVER, BackupTaskKinds.ROLLBACK}) {
      BackupTaskState state = new BackupTaskState();
      long id = state.start(kind);
      state.update(id, BackupTaskState.Status.RUNNING, "first");
      state.update(id, BackupTaskState.Status.RUNNING, "second");
      assertEquals("second", state.snapshot().lastStage);
      assertEquals("second", state.snapshot().detail);
    }
  }

  @Test
  public void previewKeepsGlobalSlotAndOldPageCannotConfirmAnotherTask() {
    BackupTaskState state = new BackupTaskState();
    long first = state.start("restore");
    state.update(first, BackupTaskState.Status.PREVIEW, "preview");
    assertTrue(state.busy());
    assertEquals(-1, state.start("maintenance"));
    assertFalse(state.confirm(first - 1));
    assertTrue(state.confirm(first));
    assertFalse(state.confirm(first));
    state.update(first, BackupTaskState.Status.SUCCEEDED, "done");
    long next = state.start("maintenance");
    state.update(next, BackupTaskState.Status.PREVIEW, "next");
    assertFalse(state.confirm(first));
    assertTrue(state.confirm(next));
  }

  @Test
  public void processDeathNeverReplaysWorkOrPretendsSuccess() {
    for (BackupTaskState.Status before :
        new BackupTaskState.Status[] {
          BackupTaskState.Status.RUNNING, BackupTaskState.Status.PREVIEW
        }) {
      BackupTaskState state = new BackupTaskState();
      state.restore(42, "恢复", before, "正在恢复");
      assertFalse(state.busy());
      assertEquals(BackupTaskState.Status.INTERRUPTED, state.snapshot().status);
      assertFalse(state.confirm(42));
      assertTrue(state.snapshot().detail.contains("未自动重试"));
    }
  }

  @Test
  public void resultSurvivesNewUiAndCompletedTaskCannotBeOverwrittenByOldCallback() {
    BackupTaskState state = new BackupTaskState();
    long id = state.start("备份");
    state.update(id, BackupTaskState.Status.SUCCEEDED, "saved");
    state.update(id, BackupTaskState.Status.FAILED, "late");
    BackupTaskState.Snapshot s = state.snapshot();
    BackupTaskState newProcess = new BackupTaskState();
    newProcess.restore(s.id, s.kind, s.status, s.detail);
    assertEquals("saved", newProcess.snapshot().detail);
    assertEquals(BackupTaskState.Status.SUCCEEDED, newProcess.snapshot().status);
    newProcess.reset();
    assertEquals(0, newProcess.snapshot().id);
    assertEquals(BackupTaskState.Status.IDLE, newProcess.snapshot().status);
    assertEquals("", newProcess.snapshot().detail);
  }

  @Test
  public void simultaneousStartsHaveExactlyOneOwner() throws Exception {
    BackupTaskState state = new BackupTaskState();
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger winners =
        new java.util.concurrent.atomic.AtomicInteger();
    Thread[] threads = new Thread[24];
    for (int i = 0; i < threads.length; i++) {
      threads[i] =
          new Thread(
              () -> {
                try {
                  go.await();
                  if (state.start("test") >= 0) winners.incrementAndGet();
                } catch (InterruptedException e) {
                  throw new AssertionError(e);
                }
              });
      threads[i].start();
    }
    go.countDown();
    for (Thread thread : threads) thread.join();
    assertEquals(1, winners.get());
  }
}
