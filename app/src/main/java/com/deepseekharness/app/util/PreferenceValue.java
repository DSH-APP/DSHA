package com.deepseekharness.app.util;

/** 兼容历史版本保存的标量类型；损坏值降级，读取不改写原配置。 */
public final class PreferenceValue {
    private PreferenceValue() { }

    public static String text(Object value, String fallback) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                ? String.valueOf(value) : fallback;
    }

    public static boolean flag(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        String text = text(value, "").trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        return fallback;
    }

    public static long integer(Object value, long fallback) {
        try { return Long.parseLong(text(value, "").trim()); }
        catch (NumberFormatException error) { return fallback; }
    }

    public static int integer(Object value, int fallback) {
        long parsed = integer(value, (long) fallback);
        return parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE ? (int) parsed : fallback;
    }
}
