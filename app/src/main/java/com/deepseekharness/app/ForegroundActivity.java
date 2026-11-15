package com.deepseekharness.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import com.deepseekharness.app.util.ForegroundReference;

/** 本应用的前台窗口，包括 WebView/Gecko；不承担 MainActivity 的导航协调职责。 */
public final class ForegroundActivity implements Application.ActivityLifecycleCallbacks {
  private static final ForegroundReference<FragmentActivity> CURRENT = new ForegroundReference<>();

  /** 已 start 未 stop 的本应用界面数；生命周期回调都在主线程。 */
  private int started;

  /** 可在桥工作线程取得弱引用快照；真正操作窗口前仍需在主线程复核。 */
  public static FragmentActivity current() {
    return CURRENT.current();
  }

  /** 必须在主线程执行；消息排队期间离开的旧宿主不能显示弹窗或读取剪贴板。 */
  public static boolean isResumed(FragmentActivity host) {
    return host != null
        && CURRENT.current() == host
        && !host.isFinishing()
        && !host.isDestroyed()
        && host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
  }

  /**
   * 本应用是否仍有另一个界面处于前台（应用内切换），而不是整体退到后台。生命周期回调都在主线程。
   *
   * <p>WebPreviewActivity.onStop 用它区分“Web → 原生页”与“Home/切 App”：前者 physical carrier
   * 通常仍可用，不应因为恢复而重建整份 Remote 基线。
   */
  public static boolean isAnotherActivityVisible(Activity activity) {
    FragmentActivity current = CURRENT.current();
    return current != null && current != activity;
  }

  @Override
  public void onActivityResumed(Activity activity) {
    if (activity instanceof FragmentActivity) CURRENT.resumed((FragmentActivity) activity);
    com.deepseekharness.app.vscreen.VirtualScreenManager.from(activity)
        .foreground()
        .resume(activity);
    HarnessService.ensureRuntimeForeground(activity);
    try {
      com.deepseekharness.app.nethealth.NetworkHealthMonitor.ensureStarted(activity);
    } catch (RuntimeException ignored) {
      // 网络健康检测只是诊断，启动失败不影响界面。
    }
  }

  private void left(Activity activity) {
    if (activity instanceof FragmentActivity) CURRENT.left((FragmentActivity) activity);
    com.deepseekharness.app.vscreen.VirtualScreenManager.from(activity)
        .foreground()
        .pause(activity);
  }

  @Override
  public void onActivityPaused(Activity activity) {
    left(activity);
  }

  @Override
  public void onActivityStopped(Activity activity) {
    left(activity);
    // 本应用全部界面都已离开前台：稍后复核后台网络与前台服务。
    if (started > 0 && --started == 0) {
      try {
        com.deepseekharness.app.nethealth.NetworkHealthMonitor.ensureStarted(activity)
            .onAppBackground();
      } catch (RuntimeException ignored) {
        // 诊断失败不影响界面
      }
    }
  }

  @Override
  public void onActivityDestroyed(Activity activity) {
    left(activity);
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle saved) {}

  @Override
  public void onActivityStarted(Activity activity) {
    started++;
  }

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle saved) {}
}
