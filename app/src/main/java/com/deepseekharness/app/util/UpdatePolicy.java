package com.deepseekharness.app.util;

import java.net.URI;
import java.util.List;

/** 更新选择只看版本码、通道和安装变体，不从 rc/low 文件名猜版本先后。 */
public final class UpdatePolicy {
    public static final String STABLE = "stable", PREVIEW = "preview";
    private UpdatePolicy() { }

    public static final class Release {
        public final int versionCode, minSdk;
        public final String version, channel, flavor, abi, url, sha256, notes, pageUrl;
        public final long bytes;
        public Release(int code, String version, String channel, String flavor, int minSdk,
                       String abi, String url, String sha256, long bytes, String notes, String pageUrl) {
            this.versionCode = code; this.version = version; this.channel = channel;
            this.flavor = flavor; this.minSdk = minSdk; this.abi = abi; this.url = url;
            this.sha256 = sha256; this.bytes = bytes; this.notes = notes; this.pageUrl = pageUrl;
        }
        public boolean valid() {
            return versionCode > 0 && minSdk >= 23 && minSdk <= 100
                    && version != null && !version.isEmpty()
                    && (STABLE.equals(channel) || PREVIEW.equals(channel))
                    && ("standard".equals(flavor) || "low".equals(flavor))
                    && "arm64-v8a".equals(abi) && https(url) && https(pageUrl)
                    && sha256 != null && sha256.matches("[a-fA-F0-9]{64}")
                    && bytes > 0 && bytes <= 1024L * 1024 * 1024;
        }
    }

    public static String defaultChannel(String version) {
        return version != null && version.contains("-") ? PREVIEW : STABLE;
    }

    /** 旧稳定包可由两种通道选中，不能用发布通道或当前偏好猜测查询来源。 */
    public static String restoreCheckedChannel(boolean recorded, String checkedChannel, String releaseChannel) {
        if (recorded) return STABLE.equals(checkedChannel) || PREVIEW.equals(checkedChannel) ? checkedChannel : null;
        // 旧选择契约明确禁止稳定通道接收预览包，只有这一种旧来源可以确定。
        return PREVIEW.equals(releaseChannel) ? PREVIEW : null;
    }

    public static Release select(List<Release> releases, int currentCode, String flavor, int sdk, String channel) {
        if (!STABLE.equals(channel) && !PREVIEW.equals(channel)) throw new IllegalArgumentException("未知更新通道");
        Release best = null;
        for (Release r : releases) {
            if (!r.valid() || !flavor.equals(r.flavor) || r.minSdk > sdk || r.versionCode <= currentCode) continue;
            if (STABLE.equals(channel) && !STABLE.equals(r.channel)) continue;
            if (best == null || r.versionCode > best.versionCode
                    || (r.versionCode == best.versionCode && STABLE.equals(r.channel))) best = r;
        }
        return best;
    }

    public static boolean https(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getFragment() == null;
        } catch (Exception e) { return false; }
    }
}
