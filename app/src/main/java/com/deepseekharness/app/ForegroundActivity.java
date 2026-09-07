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

    /** 可在桥工作线程取得弱引用快照；真正操作窗口前仍需在主线程复核。 */
    public static FragmentActivity current() { return CURRENT.current(); }

    /** 必须在主线程执行；消息排队期间离开的旧宿主不能显示弹窗或读取剪贴板。 */
    public static boolean isResumed(FragmentActivity host) {
        return host != null && CURRENT.current() == host && !host.isFinishing() && !host.isDestroyed()
                && host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
    }

    @Override public void onActivityResumed(Activity activity) {
        if (activity instanceof FragmentActivity) CURRENT.resumed((FragmentActivity) activity);
    }

    private void left(Activity activity) {
        if (activity instanceof FragmentActivity) CURRENT.left((FragmentActivity) activity);
    }
    @Override public void onActivityPaused(Activity activity) { left(activity); }
    @Override public void onActivityStopped(Activity activity) { left(activity); }
    @Override public void onActivityDestroyed(Activity activity) { left(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle saved) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) { }
}
