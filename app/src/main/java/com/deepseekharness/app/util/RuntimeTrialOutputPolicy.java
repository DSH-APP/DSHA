package com.deepseekharness.app.util;

/** 隔离试运行只把本轮自有检查插件的 loader 故障当成检查插件故障。 */
public final class RuntimeTrialOutputPolicy {
    private RuntimeTrialOutputPolicy() { }

    public static boolean ownedPluginFailure(String line) {
        if (line == null || !line.contains("dsha-runtime-check")) return false;
        return line.contains("failed to apply loader entry")
                || line.contains("failed to import loader entry");
    }
}
