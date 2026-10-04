package com.deepseekharness.app.util;

/**
 * 日志脱敏：把会泄露到 logcat / 活动日志的敏感值打码。
 * 覆盖私钥块、敏感头、查询凭据、常见键值、URL 用户信息、Bearer 和常见令牌。
 * 原生凭据持有者还可登记有界已知密钥；规则及边界由 SensitiveDataTest 锁定。
 * 任意自定义头、非常规 JSON 字段及未登记秘密不保证可识别。
 */
public final class SensitiveData {

  private SensitiveData() {}

  private static final java.util.regex.Pattern[] RULES = {
    java.util.regex.Pattern.compile(
        "(?s)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    java.util.regex.Pattern.compile(
        "(?im)((?:authorization|proxy-authorization|cookie|set-cookie|x-token)\\s*:\\s*)[^\\r\\n]+"),
    java.util.regex.Pattern.compile(
        "(?i)([?&](?:token|api[_-]?key|access[_-]?token|refresh[_-]?token|auth|secret|password)=)[^\\s&#'\"<>]+"),
    java.util.regex.Pattern.compile(
        "(?i)(\\b(?:[A-Z0-9_]*API_KEY|api[_-]?key|authorization|cookie|access[_-]?token|refresh[_-]?token|token|password|passwd|secret)\\b[\"']?\\s*[:=：]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;<>]+)"),
    java.util.regex.Pattern.compile("(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@"),
    java.util.regex.Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*"),
    java.util.regex.Pattern.compile(
        "\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{35}|AKIA[0-9A-Z]{16}|xox[abpr]-[A-Za-z0-9-]{10,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\\b")
  };
  private static final java.util.LinkedHashSet<String> KNOWN = new java.util.LinkedHashSet<>();

  /** Only trusted native credential owners register values; bound memory and allow retirement. */
  public static synchronized void registerKnownSecret(String value) {
    if (value == null || value.length() < 8 || value.length() > 4096) return;
    KNOWN.remove(value);
    KNOWN.add(value);
    while (KNOWN.size() > 32) KNOWN.remove(KNOWN.iterator().next());
  }

  public static synchronized void forgetKnownSecret(String value) {
    KNOWN.remove(value);
  }

  public static String redact(String s) {
    if (s == null) return null;
    String safe = s;
    java.util.List<String> known;
    synchronized (SensitiveData.class) {
      known = new java.util.ArrayList<>(KNOWN);
    }
    known.sort((a, b) -> Integer.compare(b.length(), a.length()));
    for (String secret : known) safe = safe.replace(secret, "***");
    String[] replacements = {
      UiText.text("[私钥已隐藏]"), "$1***", "$1***", "$1***", "$1***@", "Bearer ***", "***"
    };
    for (int i = 0; i < RULES.length; i++)
      safe = RULES[i].matcher(safe).replaceAll(replacements[i]);
    return safe;
  }
}
