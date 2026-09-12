package com.deepseekharness.app.util;

/**
 * 日志脱敏：把会泄露到 logcat / 活动日志的敏感值打码。
 * 骨架版只覆盖最关键的 API key 环境变量形态；完整版按原 SensitiveData 回填（含备份路径等）。
 */
public final class SensitiveData {

    private SensitiveData() {
    }

    public static String redact(String s) {
        if (s == null) return null;
        String safe = s.replaceAll("(?s)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", com.deepseekharness.app.util.UiText.text("[私钥已隐藏]"));
        safe = safe.replaceAll("(?im)((?:authorization|proxy-authorization|cookie|set-cookie)\\s*:\\s*)[^\\r\\n]+", "$1***");
        safe = safe.replaceAll("(?i)([?&](?:token|api[_-]?key|access[_-]?token|refresh[_-]?token|auth|secret|password)=)[^\\s&#'\"<>]+", "$1***");
        safe = safe.replaceAll("(?i)(\\b(?:[A-Z0-9_]*API_KEY|api[_-]?key|authorization|cookie|access[_-]?token|refresh[_-]?token|token|password|passwd|secret)\\b[\"']?\\s*[:=：]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;<>]+)", "$1***");
        safe = safe.replaceAll("(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@", "$1***@");
        safe = safe.replaceAll("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer ***");
        return safe.replaceAll("\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})", "***");
    }
}
