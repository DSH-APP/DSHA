package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import com.deepseekharness.app.util.FrameRatePolicy;

import java.util.ArrayList;

/** 仅在 Web 工作台可交互时请求设备允许的高刷新率。 */
final class WebFrameRate {
    private WebFrameRate() { }

    static void apply(Activity activity, View content) {
        if (activity == null || content == null) return;
        Display display = activity.getWindowManager().getDefaultDisplay();
        Display.Mode current = display == null ? null : display.getMode();
        if (current == null) return;
        ArrayList<Float> matching = new ArrayList<>();
        for (Display.Mode mode : display.getSupportedModes()) {
            // 刷新率请求不能顺带改变面板分辨率。
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                matching.add(mode.getRefreshRate());
            }
        }
        float[] rates = new float[matching.size()];
        for (int i = 0; i < rates.length; i++) rates[i] = matching.get(i);
        float peak = 0f;
        try {
            peak = Settings.System.getFloat(activity.getContentResolver(), "peak_refresh_rate");
        } catch (Settings.SettingNotFoundException | SecurityException ignored) { }
        PowerManager power = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        float selected = FrameRatePolicy.select(power != null && power.isPowerSaveMode(), peak, rates);
        setWindowRate(activity, selected);
        if (Build.VERSION.SDK_INT >= 35) Api35.setFrameRateCategory(content, selected > 0f);
    }

    static void clear(Activity activity, View content) {
        if (activity == null) return;
        setWindowRate(activity, 0f);
        if (content != null && Build.VERSION.SDK_INT >= 35) Api35.setFrameRateCategory(content, false);
    }

    private static void setWindowRate(Activity activity, float rate) {
        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        if (Math.abs(params.preferredRefreshRate - rate) < 0.01f) return;
        params.preferredRefreshRate = rate;
        activity.getWindow().setAttributes(params);
    }

    @RequiresApi(35)
    private static final class Api35 {
        static void setFrameRateCategory(View view, boolean high) {
            view.setRequestedFrameRate(high ? View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
                    : View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
        }
    }
}
