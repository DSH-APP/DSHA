package com.deepseekharness.app.util;

/** Web 工作台的高刷新率选择策略；保持纯逻辑，便于跨 Android 版本单测。 */
public final class FrameRatePolicy {
    private static final float DEFAULT_CAP_HZ = 120f;
    private static final float HIGH_REFRESH_THRESHOLD_HZ = 60.5f;
    private static final float RATE_TOLERANCE_HZ = 0.5f;

    private FrameRatePolicy() { }

    /**
     * 返回应向系统请求的刷新率；0 表示保持系统默认策略。
     *
     * <p>有用户峰值设置时绝不越过它；读不到设置时最多请求 120 Hz，避免在
     * 144/165 Hz 设备上无意扩大功耗。省电模式和仅支持 60 Hz 时均交还系统管理。</p>
     */
    public static float select(boolean powerSaveMode, float configuredPeakHz,
                               float[] supportedRatesHz) {
        if (powerSaveMode || supportedRatesHz == null || supportedRatesHz.length == 0) return 0f;
        float cap = finite(configuredPeakHz) && configuredPeakHz > 0f
                ? configuredPeakHz : DEFAULT_CAP_HZ;
        float selected = 0f;
        for (float rate : supportedRatesHz) {
            if (!finite(rate) || rate <= HIGH_REFRESH_THRESHOLD_HZ) continue;
            if (rate <= cap + RATE_TOLERANCE_HZ && rate > selected) selected = rate;
        }
        return selected;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
