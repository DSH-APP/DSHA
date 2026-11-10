package com.deepseekharness.app.transfer;

import static org.junit.Assert.*;
import org.junit.Test;

public final class PendingFileActionTest {
  @Test
  public void shareWaitsForItsVerifiedFileAndVisibleHostThenOpensOnce() {
    PendingFileAction action = new PendingFileAction("task-1", "share");
    assertEquals("", action.consume("task-2", "share", DownloadTask.Status.COMPLETE, true));
    assertEquals("", action.consume("task-1", "downloads", DownloadTask.Status.COMPLETE, true));
    assertEquals("", action.consume("task-1", "share", DownloadTask.Status.VERIFYING, true));
    assertEquals("", action.consume("task-1", "share", DownloadTask.Status.COMPLETE, false));
    assertEquals("share", action.consume("task-1", "share", DownloadTask.Status.COMPLETE, true));
    assertEquals("", action.consume("task-1", "share", DownloadTask.Status.COMPLETE, true));
    assertEquals("", action.id());
  }

  @Test
  public void saveAsWaitsForDestinationAndNeverReopensAfterCancelOrRecreation() {
    PendingFileAction action = new PendingFileAction("task-1", "saveas");
    assertEquals("", action.consume("task-1", "saveas", DownloadTask.Status.DOWNLOADING, true));
    assertEquals(
        "saveas",
        action.consume("task-1", "saveas", DownloadTask.Status.WAITING_DESTINATION, true));
    PendingFileAction restored = new PendingFileAction(action.id(), action.action());
    assertEquals(
        "", restored.consume("task-1", "saveas", DownloadTask.Status.WAITING_DESTINATION, true));
  }

  @Test
  public void terminalFailuresAndManualPauseRetireTheAutomaticAction() {
    for (DownloadTask.Status status :
        new DownloadTask.Status[] {
          DownloadTask.Status.FAILED, DownloadTask.Status.CANCELLED, DownloadTask.Status.PAUSED
        }) {
      PendingFileAction action = new PendingFileAction("task-1", "share");
      assertEquals("", action.consume("task-1", "share", status, true));
      assertEquals("", action.consume("task-1", "share", DownloadTask.Status.COMPLETE, true));
    }
  }

  @Test
  public void unknownIdsAndNonInteractiveDestinationsNeverOpenSystemUi() {
    for (PendingFileAction action :
        new PendingFileAction[] {
          new PendingFileAction("../task", "share"),
          new PendingFileAction("task-1", "downloads"),
          new PendingFileAction(null, "share")
        }) assertEquals("", action.consume("task-1", "share", DownloadTask.Status.COMPLETE, true));
  }

  @Test
  public void backgroundOrRetiredDocumentCannotLaunchLater() {
    PendingFileAction action = new PendingFileAction("task-1", "share");
    action.retire();
    assertEquals("", action.consume("task-1", "share", DownloadTask.Status.COMPLETE, true));
    PendingFileAction restored = new PendingFileAction(action.id(), action.action());
    assertEquals("", restored.consume("task-1", "share", DownloadTask.Status.COMPLETE, true));
  }
}
