package com.deepseekharness.app.util;

import java.util.List;

/** Task decisions never imply that a retained browser page may be destroyed. */
public final class BrowserTaskPolicy {
  public record Task(int id, boolean nativeRoot) {}

  private BrowserTaskPolicy() {}

  public static boolean resumeBackground(
      PreviewPageSession.Identity background,
      PreviewPageSession.Identity current,
      boolean alive,
      boolean stopping,
      boolean userStopped,
      boolean sameEngine) {
    return alive
        && sameEngine
        && !stopping
        && !userStopped
        && background != null
        && background.equals(current);
  }

  public static boolean duplicateLauncher(
      boolean restored, boolean taskRoot, boolean mainAction, boolean launcherCategory) {
    return !restored && !taskRoot && mainAction && launcherCategory;
  }

  /**
   * 选回原生主页的任务：当前存活 Main 所在任务优先；其次是 Main 与网页同处的任务（单任务布局的常态），
   * 在原任务里把 Main 移到前面，不另起任务、不清掉保留的网页；最后才是其它较新的 Main 任务（旧版本遗留）。
   */
  public static int nativeTask(List<Task> tasks, int browserTask, int preferredTask) {
    int newest = -1;
    int shared = -1;
    for (Task task : tasks) {
      if (!task.nativeRoot() || task.id() < 0) continue;
      if (task.id() == preferredTask) return task.id();
      if (task.id() == browserTask) {
        shared = task.id();
        continue;
      }
      newest = Math.max(newest, task.id());
    }
    return shared >= 0 ? shared : newest;
  }

  /** Removing a whole task is safe only when its sole activity is the empty browser host. */
  public static boolean removeEmptyTask(boolean root, int activityCount) {
    return root && activityCount == 1;
  }

  public static boolean endedHost(
      PreviewPageSession.Identity previous,
      PreviewPageSession.Identity current,
      boolean pending,
      boolean stopping,
      boolean userStopped,
      long generation) {
    return stopping
        || userStopped
        || previous != null && previous.generation() < generation
        || current != null && (pending || previous != null && !previous.equals(current));
  }
}
