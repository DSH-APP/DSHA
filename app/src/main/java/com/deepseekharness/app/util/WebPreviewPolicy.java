package com.deepseekharness.app.util;

import java.net.URI;

/** 标准版网页预览的地址与 UA 规则，不依赖 Android。 */
public final class WebPreviewPolicy {
    private WebPreviewPolicy() { }

    /** 保留实际端口；拒绝非本机服务，避免把认证 Cookie 注入外部网站。 */
    public static String loopbackBaseUrl(String value) {
        try {
            URI uri = new URI(value);
            int port = uri.getPort();
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || port < 1 || port > 65535) return null;
            return "http://127.0.0.1:" + port + "/";
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean sameService(String baseUrl, String candidate) {
        return baseUrl != null && baseUrl.equals(loopbackBaseUrl(candidate));
    }

    /** 仅用于已验证为本机页面的下载回调；Blob 仍须属于相同源。 */
    public static boolean pageDownload(String baseUrl, String candidate) {
        if (baseUrl == null || candidate == null) return false;
        return sameService(baseUrl,candidate) || candidate.startsWith("blob:") && sameService(baseUrl,candidate.substring(5))
                || candidate.startsWith("data:");
    }

    /** 电脑模式只改变平台标识，保留真实 Chrome 版本，避免伪装新内核。 */
    public static String desktopUserAgent(String userAgent) {
        if (userAgent == null) return "";
        return userAgent.replaceFirst("\\([^)]*Android[^)]*\\)", "(X11; Linux x86_64)")
                .replace(" Mobile Safari/", " Safari/");
    }
}
