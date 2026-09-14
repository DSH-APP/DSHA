package com.deepseekharness.app.ui;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.deepseekharness.app.R;

/** 两种网页内核共用安全显示区；系统栏、挖孔和输入法都不能覆盖正文。 */
public final class WebFullscreenUi {
    /** 避免全局页面边距处理覆盖网页的全屏设置。 */
    public interface Host { }

    private WebFullscreenUi() { }

    public static void install(Activity activity) {
        // 旧 Android 的 FLAG_FULLSCREEN 会阻止 adjustResize，不能依赖输入法出现时再取消全屏。
        activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        View content = activity.findViewById(android.R.id.content);
        content.setBackgroundColor(activity.getColor(R.color.surface));
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            if (PictureInPictureActivity.showing(activity)) {
                view.setPadding(0, 0, 0, 0);
                return WindowInsetsCompat.CONSUMED;
            }
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            // 顶部保留稳定安全区，避免 ROM 在键盘/焦点切换时短暂报告状态栏不可见而把正文顶上去。
            Insets stableTop = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.captionBar());
            Insets safe = Insets.max(bars, stableTop);
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard));
            return WindowInsetsCompat.CONSUMED;
        });
        applySystemBars(activity);
        ViewCompat.requestApplyInsets(content);
    }

    public static void applySystemBars(Activity activity) {
        if (PictureInPictureActivity.showing(activity)) return;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                activity.getWindow(), activity.getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(androidx.core.graphics.ColorUtils.calculateLuminance(
                activity.getColor(R.color.surface)) > 0.5);
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        // 显示状态栏，正文由 insets 避让；底部仍可通过手势唤出系统导航。
        controller.show(WindowInsetsCompat.Type.statusBars());
        controller.hide(WindowInsetsCompat.Type.navigationBars());
    }
}
