package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;

import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.RecentsPrivacyPolicy;

/**
 * 「隐藏后台」的原生守卫（应用内唯一实例，由 {@code DshaApp} 注册）。
 *
 * <ul>
 *   <li>开关开启时关闭本应用界面的最近任务截图内容（Android 13+）；当前卡片被系统保留时
 *       也不显示聊天/网页内容。
 *   <li>实验性严格模式：应用整体退到后台后结束 UI 任务，让当前卡片也不再出现在最近任务；
 *       dsh 进程、终端会话、ADB 保活与常驻通知都不受影响，只有界面任务被结束。
 *   <li>结果型外部选择器（SAF 等）在途时不结束任务，否则用户选完文件的结果回调会随界面丢失。
 * </ul>
 *
 * <p>Android 13+ 的 {@code RecentTasks} 会保留「最近可见任务」——从本应用直接滑到最近任务时
 * 当前卡片仍由系统显示；这属于平台行为，本类不做系统级注入，只在应用侧把能控的部分控制住。
 */
public final class RecentsPrivacyGuard implements Application.ActivityLifecycleCallbacks {
  /** 退到后台后等待一小段时间再结束任务，旋转/应用内切换不会触发。 */
  private static final long BACKGROUND_GRACE_MS = 700;

  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final java.util.Set<Activity> LIVE =
      java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Activity, Boolean>());
  private static final Runnable END_TASK = RecentsPrivacyGuard::endTaskIfStillBackground;

  /** 结果型外部选择器在途：此时应用退到后台不能结束任务。生命周期与启动都在主线程。 */
  private static boolean externalFlow;

  private static int started;
  private static Activity lastStopped;

  /**
   * 所有结果型外部选择器都必须经这里启动；普通权限请求与自有 Activity 直接 launch 即可。
   * 标记只在本应用重新回到前台（{@link #onActivityResumed}）后清除。
   */
  public static <I> void launch(ActivityResultLauncher<I> launcher, I input) {
    externalFlow = true;
    try {
      launcher.launch(input);
    } catch (RuntimeException unavailable) {
      externalFlow = false;
      throw unavailable;
    }
  }

  /** 设置页切换后立即对存活界面重放截图开关。 */
  public static void refreshAll() {
    for (Activity activity : LIVE) applyScreenshot(activity);
  }

  private static void applyScreenshot(Activity activity) {
    // Android 13 才提供 setRecentsScreenshotEnabled；更低版本保持系统默认，只做任务排除。
    if (Build.VERSION.SDK_INT < 33 || activity.isFinishing() || activity.isDestroyed()) return;
    boolean hide = new ConfigStore(activity).isHideFromRecents();
    try {
      activity.setRecentsScreenshotEnabled(!RecentsPrivacyPolicy.hideRecentsScreenshot(hide));
    } catch (RuntimeException ignored) {
      // 系统拒绝只影响最近任务缩略图，不影响界面本身。
    }
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle saved) {
    LIVE.add(activity);
    applyScreenshot(activity);
  }

  @Override
  public void onActivityStarted(Activity activity) {
    started++;
    MAIN.removeCallbacks(END_TASK);
  }

  @Override
  public void onActivityResumed(Activity activity) {
    externalFlow = false;
    applyScreenshot(activity);
  }

  @Override
  public void onActivityStopped(Activity activity) {
    if (started > 0) started--;
    lastStopped = activity;
    if (started != 0 || activity.isChangingConfigurations()) return;
    MAIN.removeCallbacks(END_TASK);
    MAIN.postDelayed(END_TASK, BACKGROUND_GRACE_MS);
  }

  @Override
  public void onActivityDestroyed(Activity activity) {
    LIVE.remove(activity);
  }

  @Override
  public void onActivityPaused(Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle out) {}

  private static void endTaskIfStillBackground() {
    if (started > 0) return;
    Activity host = lastStopped;
    if (host == null || host.isFinishing() || host.isDestroyed()) return;
    boolean pictureInPicture = false;
    boolean multiWindow = false;
    for (Activity activity : LIVE) {
      if (activity.isInPictureInPictureMode()) pictureInPicture = true;
      if (activity.isInMultiWindowMode()) multiWindow = true;
    }
    ConfigStore config = new ConfigStore(host);
    if (RecentsPrivacyPolicy.onAppBackground(
            config.isHideFromRecents(),
            config.isHideRecentsStrict(),
            externalFlow,
            pictureInPicture,
            multiWindow)
        != RecentsPrivacyPolicy.BackgroundAction.END_TASK) return;
    lastStopped = null;
    try {
      com.deepseekharness.app.core.DiagnosticLog.record(
          host, "RECENTS_STRICT_END", "tasks=" + appTaskCount(host));
      boolean removed = false;
      ActivityManager manager =
          (ActivityManager) host.getSystemService(android.content.Context.ACTIVITY_SERVICE);
      if (manager != null)
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
          task.finishAndRemoveTask();
          removed = true;
        }
      // 任务枚举被系统拒绝时，至少结束当前界面所属任务。
      if (!removed) host.finishAndRemoveTask();
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "RECENTS_STRICT_END: " + unavailable.getClass().getSimpleName());
    }
  }

  private static int appTaskCount(Activity host) {
    try {
      ActivityManager manager =
          (ActivityManager) host.getSystemService(android.content.Context.ACTIVITY_SERVICE);
      return manager == null ? -1 : manager.getAppTasks().size();
    } catch (RuntimeException unavailable) {
      return -1;
    }
  }
}
