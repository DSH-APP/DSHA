package com.deepseekharness.app.util;

/** 网页文件边界；文件名不能变成路径，长度声明不能越过本地容量限制。 */
public final class WebTransferPolicy {
    public static final long DOWNLOAD_LIMIT = 2L * 1024 * 1024 * 1024;
    public static final long UPLOAD_LIMIT = 256L * 1024 * 1024;
    public static final int UPLOAD_COUNT = 20;
    private WebTransferPolicy() { }
    public static String fileName(String name) {
        String safe = name == null ? "" : name.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) return "download.bin";
        if (safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 240) return safe;
        int dot=safe.lastIndexOf('.');
        String suffix=dot>0?safe.substring(dot):"";
        if (suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>64) suffix="";
        String stem=safe.substring(0,safe.length()-suffix.length());
        while ((stem+suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>240)
            stem=stem.substring(0,stem.offsetByCodePoints(stem.length(),-1));
        return stem+suffix;
    }
    public static void checkSize(long actual, long expected, long limit, boolean complete) {
        if (actual < 0 || actual > limit || expected > limit) throw new IllegalArgumentException("文件超过允许大小");
        if (complete && expected >= 0 && actual != expected) throw new IllegalArgumentException("文件未下载完整，请重试");
    }
}
