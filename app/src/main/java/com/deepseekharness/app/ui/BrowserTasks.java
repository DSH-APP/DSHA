package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.deepseekharness.app.util.BrowserTaskPolicy;
import com.deepseekharness.app.util.PreviewPageSession;
import com.deepseekharness.app.util.TaskNavigationPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Explicit native navigation, including tasks retained from versions with shared affinities. */
final class BrowserTasks {
  static final String NATIVE_HOME = "dsha-native-home";
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
        && (intent.getBooleanExtra(NATIVE_HOME, false)
            || intent.getBooleanExtra("open_plugins", false)
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

  /** A confirmed renderer loss cannot keep Main waiting for that page's content forever. */
  static void browserUnavailable(PictureInPictureActivity owner) {
    if (background != null && background.owner().get() == owner) {
      background = null;
      dispatched = null;
      trace("CANCEL_RENDERER_GONE");
    }
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

  /**
   * 通知等应用入口：回到仍存活、可整屏显示的原网页。没有登记“返回原网页”时（例如用户正停在 Web
   * 时拉下通知），按 Home 相同的规则登记后再派发；用户主动隐藏的会话与未就绪的页面不派发。
   */
  static boolean returnToLiveBrowser(Context context) {
    if (resumeBackground(context, true)) return true;
    // 已登记但仍在核验 / 等内容：保持登记，交给 Main 的既有返回路由，不覆盖。
    if (returnPending()) return false;
    PictureInPictureActivity owner = BrowserConversationRoute.owner();
    if (owner == null || owner.conversationHidden()) return false;
    rememberBackground(owner);
    if (resumeBackground(context, true)) return true;
    // 未能派发：撤销本次登记，避免之后进入 Main 时被意外带回网页。
    nativeNavigation();
    return false;
  }

  /** 仍有待回到的原网页（含等待核验 / 内容就绪）；只读，不派发。 */
  static boolean returnPending() {
    Background pending = background;
    if (pending == null) return false;
    var owner = pending.owner().get();
    return owner != null && !owner.isFinishing() && !owner.isDestroyed();
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
    // Android/ROM may reject startActivityInTask even for our own retained task. The shared
    // affinity plus REORDER_TO_FRONT reuses a host in the Main task; a host elsewhere (PiP task)
    // is reached by WebPreviewActivity forwarding. NEW_TASK, without CLEAR_* or MULTIPLE_TASK.
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
    // Main 与 Web 同一任务后，Main 可能不是任务根（例如通知先开了 Web）；重复启动器包装在调用前已退出。
    if (!activity.isFinishing() && !activity.isDestroyed())
      nativeMain = new WeakReference<>(activity);
    reconcileRecents(activity, activity.getTaskId());
  }

  /** 存活 Main 所在任务；没有时为 -1。 */
  private static int liveMainTask() {
    Activity main = nativeMain.get();
    return main == null || main.isFinishing() || main.isDestroyed() ? -1 : main.getTaskId();
  }

  /**
   * 把 intent 派发进指定的既有任务（不新建任务、不清栈）。系统或 ROM 拒绝、或任务已不存在时返回 false，
   * 由调用方决定兜底。
   */
  static boolean startInTask(Context context, int taskId, Intent intent) {
    if (taskId < 0) return false;
    try {
      ActivityManager manager =
          (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      if (manager == null) return false;
      for (ActivityManager.AppTask task : manager.getAppTasks())
        if (task.getTaskInfo().id == taskId) {
          task.startActivity(context, intent, null);
          return true;
        }
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "TASK_DISPATCH: " + unavailable.getClass().getSimpleName());
    }
    return false;
  }

  static final String FORWARDED = "dsha-browser-forwarded";

  /**
   * Web 宿主不再用 singleTask 独占任务，同一时刻仍只允许一个存活宿主：新建实例发现已有存活宿主
   * （例如它在画中画任务里）时，把本次请求原样转交给它所在的任务。成功返回 true，调用方结束新实例；
   * 已转交过一次或派发失败时返回 false，新实例照常接管，避免来回转交。
   */
  static boolean forwardToLiveBrowser(Activity created) {
    Intent request = created.getIntent();
    PictureInPictureActivity owner = BrowserConversationRoute.owner();
    if (owner == null || owner == created || request == null) return false;
    if (request.getBooleanExtra(FORWARDED, false)) return false;
    Intent forward =
        new Intent(request)
            .setComponent(new android.content.ComponentName(created, owner.getClass()))
            .putExtra(FORWARDED, true);
    forward.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if (!startInTask(created, owner.getTaskId(), forward)) {
      trace("FORWARD_REJECTED");
      return false;
    }
    trace("FORWARD_LIVE_HOST");
    return true;
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
    return navigationIntent(context).putExtra("open_launch", true);
  }

  static Intent navigationIntent(Context context) {
    return new Intent(context, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(NATIVE_HOME, true);
  }

  /** Main Back exits the UI, including a legacy task with a hidden browser below Main. */
  static void exitMain(Activity activity) {
    nativeNavigation();
    reconcileRecents(activity, activity.getTaskId());
    // A retained hidden browser may be the task immediately behind Main. Bring
    // the launcher forward first, or moving Main back could resume that browser,
    // whose hidden-mode callback would immediately reopen Main.
    try {
      activity.startActivity(
          new Intent(Intent.ACTION_MAIN)
              .addCategory(Intent.CATEGORY_HOME)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (android.content.ActivityNotFoundException unavailable) {
      android.util.Log.w("DSHA", "HOME_TASK_UNAVAILABLE");
    }
    // finish() would expose the retained browser in a shared legacy stack. Moving
    // the complete task preserves its page and mirrors current Android launcher Back.
    activity.moveTaskToBack(true);
  }

  /** One Main card represents the app; browser/PiP hosts and old Main shells stay retained. */
  static boolean reconcileRecents(Context context, int preferredMainTask) {
    boolean hideAll = false;
    try {
      ActivityManager manager =
          (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      if (manager == null) return false;
      hideAll = new com.deepseekharness.app.core.ConfigStore(context).isHideFromRecents();
      List<ActivityManager.AppTask> appTasks = manager.getAppTasks();
      if (appTasks.isEmpty()) return false;
      List<BrowserTaskPolicy.Task> choices = new ArrayList<>();
      for (ActivityManager.AppTask task : appTasks) {
        var info = task.getTaskInfo();
        choices.add(new BrowserTaskPolicy.Task(info.id, nativeHost(context, info)));
      }
      int selected =
          BrowserTaskPolicy.nativeTask(
              choices, BrowserConversationRoute.taskId(), preferredMainTask);
      for (ActivityManager.AppTask task : appTasks) {
        var info = task.getTaskInfo();
        boolean nativeHost = nativeHost(context, info);
        var visibility =
            TaskNavigationPolicy.recents(
                info.id,
                nativeHost,
                !nativeHost && browserRoot(context, info.baseActivity),
                selected,
                hideAll);
        if (visibility != TaskNavigationPolicy.Recents.KEEP)
          task.setExcludeFromRecents(visibility == TaskNavigationPolicy.Recents.HIDE);
      }
      return true;
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "TASK_RECENTS_ROUTE: " + unavailable.getClass().getSimpleName());
      if (hideAll)
        com.deepseekharness.app.core.DiagnosticLog.record(
            context, "RECENTS_HIDE_APPLY", unavailable.toString());
      return false;
    }
  }

  /**
   * 把「隐藏后台」偏好立即应用到本应用全部任务：开启时全部隐藏；关闭时先恢复显示，再按现有的
   * 单张主卡规则重新分类。偏好由调用方先保存；系统拒绝时返回 false，由界面提示并在下次 Main
   * 恢复时自动重试，不崩溃也不重放。
   */
  static boolean applyRecentsPreference(Context context) {
    try {
      ActivityManager manager =
          (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      if (manager == null) return false;
      List<ActivityManager.AppTask> appTasks = manager.getAppTasks();
      boolean hide = new com.deepseekharness.app.core.ConfigStore(context).isHideFromRecents();
      if (appTasks.isEmpty()) {
        // 没有可重放的本应用任务时不能报告成功：设置页会提示、下次 Main 恢复再试。
        com.deepseekharness.app.core.DiagnosticLog.record(
            context, "RECENTS_HIDE_APPLY", "NO_APP_TASKS");
        return false;
      }
      for (ActivityManager.AppTask task : appTasks) task.setExcludeFromRecents(hide);
      if (!hide) return reconcileRecents(context, liveMainTask());
      return exclusionApplied(context, true);
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "TASK_RECENTS_HIDE: " + unavailable.getClass().getSimpleName());
      com.deepseekharness.app.core.DiagnosticLog.record(
          context, "RECENTS_HIDE_APPLY", unavailable.toString());
      return false;
    }
  }

  /**
   * 重读任务列表，确认 root Intent 上的「排除出最近任务」标志已与期望一致。
   *
   * <p>{@code setExcludeFromRecents} 不抛异常也不代表系统真的改到了任务（例如 ROM 只改缓存或
   * 返回空任务列表）；刷完立即回读，避免「标记成功但实际未生效」的假成功。
   */
  private static boolean exclusionApplied(Context context, boolean hide) {
    boolean verified = false;
    try {
      ActivityManager manager =
          (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      List<ActivityManager.AppTask> appTasks = manager == null ? List.of() : manager.getAppTasks();
      if (appTasks.isEmpty()) return false;
      for (ActivityManager.AppTask task : appTasks) {
        var info = task.getTaskInfo();
        // 个别 ROM / 测试替身不提供 root Intent：标记不可核对，但不据此判失败。
        if (info == null || info.baseIntent == null) continue;
        boolean excluded =
            (info.baseIntent.getFlags() & android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                != 0;
        if (excluded != hide) {
          com.deepseekharness.app.core.DiagnosticLog.record(
              context, "RECENTS_HIDE_VERIFY", "FLAG_MISMATCH");
          return false;
        }
        verified = true;
      }
      if (!verified) android.util.Log.d("DSHA", "TASK_RECENTS_VERIFY: no base intent to read");
      return true;
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "TASK_RECENTS_VERIFY: " + unavailable.getClass().getSimpleName());
      return false;
    }
  }

  private static boolean ownRoot(Context context, android.content.ComponentName root) {
    return root != null && context.getPackageName().equals(root.getPackageName());
  }

  private static boolean mainRoot(Context context, android.content.ComponentName root) {
    return ownRoot(context, root) && MainActivity.class.getName().equals(root.getClassName());
  }

  /** 以 Main 为根的任务，或存活 Main 所在的任务（Web 先开、Main 后加入的同一任务）。 */
  private static boolean nativeHost(Context context, ActivityManager.RecentTaskInfo info) {
    int main = liveMainTask();
    return mainRoot(context, info.baseActivity) || main >= 0 && info.id == main;
  }

  private static boolean browserRoot(Context context, android.content.ComponentName root) {
    return ownRoot(context, root) && WebPreviewActivity.class.getName().equals(root.getClassName());
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
        choices.add(new BrowserTaskPolicy.Task(info.id, nativeHost(context, info)));
      }
      int selected = BrowserTaskPolicy.nativeTask(choices, browserTask, preferredTask);
      for (ActivityManager.AppTask task : appTasks)
        if (selected >= 0 && task.getTaskInfo().id == selected) {
          task.startActivity(context, homeIntent(context), null);
          reconcileRecents(context, selected);
          return;
        }
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "NATIVE_TASK_ROUTE: " + unavailable.getClass().getSimpleName());
    }
    // Let Android reuse the app's native affinity even if task enumeration is denied.
    // MULTIPLE_TASK used to create a fresh Main on every Back when task lookup failed.
    context.startActivity(homeIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
