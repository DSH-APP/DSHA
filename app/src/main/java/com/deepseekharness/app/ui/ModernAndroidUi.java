package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.deepseekharness.app.R;

/** 统一处理 Android 15+ 强制铺满窗口后的状态栏、挖孔和输入法区域。 */
public final class ModernAndroidUi implements Application.ActivityLifecycleCallbacks {
    @Override public void onActivityPostCreated(Activity activity, Bundle saved) {
        applyInsets(activity);
    }

    private void applyInsets(Activity activity) {
        if (activity instanceof WebFullscreenUi.Host) return;
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 29) content.setForceDarkAllowed(false);
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        int color = activity.getColor(R.color.surface);
        activity.getWindow().setNavigationBarColor(android.os.Build.VERSION.SDK_INT >= 26
                ? color : activity.getColor(R.color.terminal_surface));
        content.setBackgroundColor(color);
        boolean light = ColorUtils.calculateLuminance(color) > 0.5;
        androidx.core.view.WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), content);
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
        final int left = content.getPaddingLeft(), top = content.getPaddingTop();
        final int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(left + bars.left, top + bars.top, right + bars.right,
                    bottom + Math.max(bars.bottom, keyboard));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
    }

    @Override public void onActivityCreated(Activity activity, Bundle saved) {
        if (android.os.Build.VERSION.SDK_INT < 29)
            activity.getWindow().getDecorView().post(() -> { if (!activity.isFinishing()) applyInsets(activity); });
    }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle out) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
