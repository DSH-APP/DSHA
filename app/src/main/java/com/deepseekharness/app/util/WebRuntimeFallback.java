package com.deepseekharness.app.util;

/** 兼容重试只由已确认的进程退出触发；等待时间、插件报错和用户停止都不能触发。 */
public final class WebRuntimeFallback {
    private WebRuntimeFallback() { }

    /**
     * 会触发一次性兼容重试的运行时集合：proroot 与 bxroot（都是实验运行时，
     * 退出后用 proot 重试一次）。proot 本身是兜底，不再重试。
     */
    private static boolean isExperimental(String runtime) {
        return "proroot".equals(runtime) || "bxroot".equals(runtime);
    }

    public static boolean shouldRetry(String runtime, boolean retried, boolean hadAuth,
                                      boolean pluginFailure, boolean current, Integer exitCode) {
        return isExperimental(runtime) && !retried && !hadAuth && !pluginFailure && current && exitCode != null;
    }

    /** 连续失败达到 {@code failures} 次后，实验运行时不再重试，直接切回 proot（三振切回）。 */
    public static final int FORCE_PROOT_FAILURES = 3;

    /** {@code failures} 达到上限且当前选的是实验运行时 → 自动改回 proot。proot 本身不触发。 */
    public static boolean shouldForceProot(int failures, String runtime) {
        return failures >= FORCE_PROOT_FAILURES && isExperimental(runtime);
    }
}
