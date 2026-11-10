package com.deepseekharness.app.transfer;

/** One explicit foreground destination action, consumed before launching any system UI. */
public final class PendingFileAction {
  private String id;
  private final String action;

  public PendingFileAction(String id, String action) {
    this.id = id != null && id.matches("[A-Za-z0-9_-]{1,128}") ? id : "";
    this.action = "share".equals(action) || "saveas".equals(action) ? action : "";
  }

  public String id() {
    return id;
  }

  public String action() {
    return action;
  }

  public void retire() {
    id = "";
  }

  public String consume(
      String taskId, String destination, DownloadTask.Status status, boolean resumed) {
    if (!resumed
        || id.isEmpty()
        || action.isEmpty()
        || !id.equals(taskId)
        || !action.equals(destination)) return "";
    if (status == DownloadTask.Status.PAUSED
        || status == DownloadTask.Status.FAILED
        || status == DownloadTask.Status.CANCELLED) {
      id = ""; // Retrying a failed transfer must not replay a previous Share request.
      return "";
    }
    if (action.equals("share") && status == DownloadTask.Status.COMPLETE
        || action.equals("saveas") && status == DownloadTask.Status.WAITING_DESTINATION) {
      id = "";
      return action;
    }
    return "";
  }
}
