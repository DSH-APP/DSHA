package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.deepseekharness.app.util.BrowserTaskPolicy;
import com.deepseekharness.app.util.PreviewPageSession;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Explicit native navigation, including tasks retained from versions with shared affinities. */
final class BrowserTasks {
  private static WeakReference<Activity> nativeMain = new WeakReference<>(null);

  private record Background(
      WeakReference<PictureInPictureActivity> owner,
      PreviewPageSession.Identity identity,
      boolean engine) {}

  private static Background background;
  private static Background dispatched;

  private BrowserTasks() {}

  static boolean launcher(Intent intent) {
    return intent != null
        && Intent.ACTION_MAIN.equals(intent.getAction())
        && intent.hasCategory(Intent.CATEGORY_LAUNCHER);
  }

  static boolean nativeDestination(Intent intent) {
    // A launcher may replay Main's original action-less base Intent and stale open_launch.
    // Only an actual destination request overrides the pending browser return here;
    // Back/Home already cancels it synchronously in openMain/hideConversation.
    return intent != null
        && (intent.getBooleanExtra("open_plugins", false)
            || intent.getBooleanExtra("open_terminal", false)
            || intent.getBooleanExtra("open_install", false)
            || intent.getBooleanExtra("open_web", false));
  }

  static void entry(String stage, Intent intent) {
    android.util.Log.i(
        "DSHA",
        "BROWSER_RETURN ENTRY_"
            + stage
            + " "
            + (nativeDestination(intent)
                ? "DESTINATION"
                : launcher(intent) ? "LAUNCHER" : "RETAINED"));
  }

  private static void trace(String stage) {
    android.util.Log.i("DSHA", "BROWSER_RETURN " + stage);
  }

  /** Home means a temporary background visit, not a request for the native start page. */
  static void rememberBackground(PictureInPictureActivity owner) {
    var controller = com.deepseekharness.app.core.HarnessController.get(owner);
    var identity = controller.getReadyWebPageIdentity();
    if (identity != null
        && !controller.isStopping()
        && !controller.isUserStopped()
        && !owner.isFinishing()
        && !owner.isDestroyed()
        && owner.pictureInPictureContentReady()) {
      background =
          new Background(new WeakReference<>(owner), identity, PreviewFallback.preferred(owner));
      dispatched = null;
      trace("SAVED");
    } else trace("SAVE_NOT_READY");
  }

  static void nativeNavigation() {
    if (background != null) trace("CANCEL_NATIVE");
    background = null;
    dispatched = null;
  }

  /** Only the original browser reaching full-screen resume acknowledges the Home return. */
  static void browserResumed(PictureInPictureActivity owner) {
    if (background != null
        && background.owner().get() == owner
        && !owner.conversationHidden()
        && !PictureInPictureActivity.showing(owner)) {
      background = null;
      dispatched = null;
      trace("ACK_WEB_RESUMED");
    }
  }

  static boolean resumeBackground(Context context) {
    return resumeBackground(context, false);
  }

  /** A new launcher entry can retry; its matching onResume does not dispatch twice. */
  static boolean resumeBackground(Context context, boolean newEntry) {
    Background pending = background;
    if (pending == null) {
      trace("EMPTY");
      return false;
    }
    var owner = pending.owner().get();
    if (owner == null || owner.isFinishing() || owner.isDestroyed()) {
      background = null;
      dispatched = null;
      trace("OWNER_GONE");
      return false;
    }
    var controller = com.deepseekharness.app.core.HarnessController.get(context);
    if (controller.getReadyWebPageIdentity() == null
        && controller.isWebSessionRetained(pending.identity())
        && !controller.isWebSessionExited(pending.identity())) {
      controller.requestWebSessionRecheck(
          () -> {
            if (background == pending
                && pending.identity().equals(controller.getReadyWebPageIdentity()))
              resumeBackground(context, false);
          });
      trace("WAIT_VERIFICATION");
      return false;
    }
    if (!BrowserTaskPolicy.resumeBackground(
        pending.identity(),
        controller.getReadyWebPageIdentity(),
        true,
        controller.isStopping(),
        controller.isUserStopped(),
        pending.engine() == PreviewFallback.preferred(context))) {
      background = null;
      dispatched = null;
      trace("REJECTED_STATE");
      return false;
    }
    if (!owner.pictureInPictureContentReady()) {
      trace("WAIT_CONTENT");
      return false;
    }
    if (dispatched == pending && !newEntry) {
      trace("IN_FLIGHT");
      return true;
    }
    Intent full =
        new Intent(context, owner.getClass())
            .putExtra(BrowserConversationRoute.MODE, "FULL")
            .putExtra(BrowserConversationRoute.GENERATION, pending.identity().generation())
            .putExtra(BrowserConversationRoute.INSTANCE, pending.identity().instanceId())
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    try {
      dispatched = pending;
      ActivityManager manager =
          (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      if (manager != null)
        for (ActivityManager.AppTask task : manager.getAppTasks())
          if (task.getTaskInfo().id == owner.getTaskId()) {
            task.startActivity(context, full, null);
            trace("DISPATCH_TASK");
            return true;
          }
    } catch (RuntimeException unavailable) {
      trace("TASK_ROUTE_REJECTED");
      android.util.Log.w(
          "DSHA", "BROWSER_BACKGROUND_TASK: " + unavailable.getClass().getSimpleName());
    }
    // Android/ROM may reject startActivityInTask even for our own retained task. The
    // explicit singleTask component and its existing affinity select the same live host;
    // NEW_TASK is required for this fallback, without CLEAR_* or MULTIPLE_TASK.
    try {
      context.startActivity(full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      trace("DISPATCH_COMPONENT");
      return true;
    } catch (RuntimeException unavailable) {
      dispatched = null;
      trace("DISPATCH_RETRYABLE");
      android.util.Log.w(
          "DSHA", "BROWSER_BACKGROUND_ROUTE: " + unavailable.getClass().getSimpleName());
      return false;
    }
  }

  static void rememberMain(Activity activity) {
    if (!activity.isFinishing()
        && !activity.isDestroyed()
        && activity.isTaskRoot()
        && activity.getTaskId() != BrowserConversationRoute.taskId())
      nativeMain = new WeakReference<>(activity);
  }

  static boolean duplicateLauncher(Activity activity, Bundle restored) {
    Intent intent = activity.getIntent();
    return intent != null
        && BrowserTaskPolicy.duplicateLauncher(
            restored != null,
            activity.isTaskRoot(),
            Intent.ACTION_MAIN.equals(intent.getAction()),
            intent.hasCategory(Intent.CATEGORY_LAUNCHER));
  }

  static Intent homeIntent(Context context) {
    return new Intent(context, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("open_launch", true);
  }

  static void openMain(Context context) {
    nativeNavigation();
    int browserTask =
        context instanceof PictureInPictureActivity activity
            ? activity.getTaskId()
            : BrowserConversationRoute.taskId();
    Activity preferred = nativeMain.get();
    int preferredTask =
        preferred != null && !preferred.isFinishing() && !preferred.isDestroyed()
            ? preferred.getTaskId()
            : -1;
    try {
      ActivityManager manager =
          (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      List<ActivityManager.AppTask> appTasks = manager == null ? List.of() : manager.getAppTasks();
      List<BrowserTaskPolicy.Task> choices = new ArrayList<>();
      for (ActivityManager.AppTask task : appTasks) {
        var info = task.getTaskInfo();
        choices.add(
            new BrowserTaskPolicy.Task(
                info.id,
                info.baseActivity != null
                    && context.getPackageName().equals(info.baseActivity.getPackageName())
                    && MainActivity.class.getName().equals(info.baseActivity.getClassName())));
      }
      int selected = BrowserTaskPolicy.nativeTask(choices, browserTask, preferredTask);
      for (ActivityManager.AppTask task : appTasks)
        if (selected >= 0 && task.getTaskInfo().id == selected) {
          task.startActivity(context, homeIntent(context), null);
          return;
        }
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "NATIVE_TASK_ROUTE: " + unavailable.getClass().getSimpleName());
    }
    // No independent native root exists, including a legacy Web/Main/Web mixed task.
    // Create only the native shell; the live browser task and its ViewModel stay intact.
    context.startActivity(
        homeIntent(context)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
  }

  static boolean finishEmptyHost(Activity activity) {
    int members = -1;
    try {
      ActivityManager manager =
          (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
      if (manager != null)
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
          var info = task.getTaskInfo();
          if (info.id == activity.getTaskId()) {
            members = info.numActivities;
            break;
          }
        }
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "BROWSER_TASK_MEMBERS: " + unavailable.getClass().getSimpleName());
    }
    if (BrowserTaskPolicy.removeEmptyTask(activity.isTaskRoot(), members))
      activity.finishAndRemoveTask();
    else activity.finish(); // Never remove native pages or another host in a shared legacy task.
    return activity.isFinishing();
  }
}
