package com.deepseekharness.app.util;

import java.nio.charset.StandardCharsets;

/** DSHA 自有脚本与内置插件的窄版本标记；不使用 APK 版本号推导数据格式。 */
public final class ManagedAssetVersion {
    private static final String PREFIX = "DSHA_MANAGED_ASSETS_V2\n";
    private ManagedAssetVersion() { }

    public static boolean validRuntimeId(String runtimeId) {
        return runtimeId != null && runtimeId.matches("[a-f0-9]{64}");
    }

    public static String marker(String runtimeId) {
        if (!validRuntimeId(runtimeId)) throw new IllegalArgumentException("runtimeId");
        return PREFIX + runtimeId + "\n";
    }

    public static byte[] bytes(String runtimeId) {
        return marker(runtimeId).getBytes(StandardCharsets.US_ASCII);
    }

    public static boolean current(String value, String runtimeId) {
        return validRuntimeId(runtimeId) && marker(runtimeId).equals(value);
    }
}
