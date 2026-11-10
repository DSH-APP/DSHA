package com.deepseekharness.app.util;

/** UI navigation and recents visibility never authorize stopping the runtime or deleting data. */
public final class TaskNavigationPolicy {
  public enum Recents {
    KEEP,
    SHOW,
    HIDE
  }

  public enum Back {
    POP_PAGE,
    BACKGROUND
  }

  private TaskNavigationPolicy() {}

  public static Back back(int nestedPages, boolean stateSaved) {
    return nestedPages > 0 && !stateSaved ? Back.POP_PAGE : Back.BACKGROUND;
  }

  public static Recents recents(
      int taskId, boolean mainRoot, boolean browserRoot, int selectedMainTask) {
    return recents(taskId, mainRoot, browserRoot, selectedMainTask, false);
  }

  /**
   * {@code hideAll} 是用户显式的「隐藏后台」偏好：隐藏本应用全部有效任务，优先于单张主卡分类；
   * 无效任务仍不碰，避免把系统占位或未知任务写坏。
   */
  public static Recents recents(
      int taskId, boolean mainRoot, boolean browserRoot, int selectedMainTask, boolean hideAll) {
    if (taskId < 0) return Recents.KEEP;
    if (hideAll) return Recents.HIDE;
    if (mainRoot) {
      // Without an identified Main, do not hide the user's last route back into the app.
      if (selectedMainTask < 0) return Recents.KEEP;
      return taskId == selectedMainTask ? Recents.SHOW : Recents.HIDE;
    }
    return browserRoot ? Recents.HIDE : Recents.KEEP;
  }
}
