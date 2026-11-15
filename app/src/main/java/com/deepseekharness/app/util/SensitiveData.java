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
        "(?s)-----BEGIN ((?:RSA |EC |OPENSSH |ENCRYPTED |DSA )?)PRIVATE KEY-----.*?(?:-----END \\1PRIVATE KEY-----|\\z)"),
    java.util.regex.Pattern.compile(
        "(?im)((?:authorization|proxy-authorization|cookie|set-cookie|x-token)\\s*:\\s*)[^\\r\\n]+"),
    java.util.regex.Pattern.compile(
        "(?i)([?&](?:token|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|auth|secret|password|code|state|authorization_code|code_verifier|code_challenge|authorize_id)=)[^\\s&#'\"<>]+"),
    java.util.regex.Pattern.compile(
        "(?i)(\\b(?:[A-Z0-9_]*API_KEY|api[_-]?key|authorization|cookie|access[_-]?token|refresh[_-]?token|id[_-]?token|token|password|passwd|secret|code_verifier|code_challenge|authorization_code|authorize_id)\\b[\"']?\\s*[:=：]\\s*)(?:\"[^\"]*(?:\"|\\z)|'[^']*(?:'|\\z)|[^\\s,;<>&#]+)"),
    java.util.regex.Pattern.compile("(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@"),
    java.util.regex.Pattern.compile("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/-]+=*"),
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

  /**
   * 终端快照专用：用完整上下文匹配，但只返回各对 [start, end) 原始位置的内容。
   * 所有规则在原文上独立匹配，避免先替换已知秘密破坏多行结构；掩码只分配选中范围的位图。
   */
  static String[] redactRanges(String source, int... boundaries) {
    if (boundaries.length % 2 != 0) throw new IllegalArgumentException("Unpaired range");
    RedactedRange[] ranges = new RedactedRange[boundaries.length / 2];
    for (int i = 0; i < ranges.length; i++) {
      int start = boundaries[2 * i], end = boundaries[2 * i + 1];
      if (start < 0 || start > end || end > source.length())
        throw new IllegalArgumentException("Invalid range");
      // 即使调用者把范围放在代理对中间，也只向内收缩，不返回孤立代理项。
      if (start < end && start > 0 && Character.isLowSurrogate(source.charAt(start))) start++;
      if (start < end && end < source.length() && Character.isHighSurrogate(source.charAt(end - 1)))
        end--;
      ranges[i] = new RedactedRange(start, end);
    }
    java.util.List<String> known;
    synchronized (SensitiveData.class) {
      known = new java.util.ArrayList<>(KNOWN);
    }
    for (String secret : known) hideKnownSecret(source, ranges, secret);
    for (int rule = 0; rule < RULES.length; rule++) {
      java.util.regex.Matcher matcher = RULES[rule].matcher(source);
      while (matcher.find()) {
        // 规则 1..5 的第一组是可保留的标签；URL 还保留结尾的 @。
        int start = rule >= 1 && rule <= 5 ? matcher.end(1) : matcher.start();
        int end = rule == 4 ? matcher.end() - 1 : matcher.end();
        hide(source, ranges, start, end, rule == 0);
      }
    }
    String[] result = new String[ranges.length];
    for (int i = 0; i < ranges.length; i++) result[i] = ranges[i].render(source);
    return result;
  }

  private static void hideKnownSecret(String source, RedactedRange[] ranges, String secret) {
    // KMP 保留重叠命中，但不反复比较/涂写同一段长重复值；登记值最多 4096 字符。
    int[] fallback = new int[secret.length()];
    for (int i = 1, matched = 0; i < secret.length(); i++) {
      while (matched > 0 && secret.charAt(i) != secret.charAt(matched))
        matched = fallback[matched - 1];
      if (secret.charAt(i) == secret.charAt(matched)) matched++;
      fallback[i] = matched;
    }
    int pendingStart = -1, pendingEnd = -1;
    for (int i = 0, matched = 0; i < source.length(); i++) {
      while (matched > 0 && source.charAt(i) != secret.charAt(matched))
        matched = fallback[matched - 1];
      if (source.charAt(i) == secret.charAt(matched)) matched++;
      if (matched == secret.length()) {
        int start = i + 1 - secret.length();
        if (pendingEnd < start) {
          if (pendingStart >= 0) hide(source, ranges, pendingStart, pendingEnd, false);
          pendingStart = start;
        }
        pendingEnd = i + 1;
        matched = fallback[matched - 1];
      }
    }
    if (pendingStart >= 0) hide(source, ranges, pendingStart, pendingEnd, false);
  }

  private static void hide(
      String source, RedactedRange[] ranges, int start, int end, boolean privateKey) {
    if (start >= end) return;
    if (start > 0 && Character.isLowSurrogate(source.charAt(start))) start--;
    if (end < source.length() && Character.isHighSurrogate(source.charAt(end - 1))) end++;
    for (RedactedRange range : ranges) range.hide(start, end, privateKey);
  }

  private static final class RedactedRange {
    final int start, end;
    final java.util.BitSet hidden = new java.util.BitSet();
    final java.util.BitSet privateKeys = new java.util.BitSet();

    RedactedRange(int start, int end) {
      this.start = start;
      this.end = end;
    }

    void hide(int from, int to, boolean privateKey) {
      int first = Math.max(start, from), last = Math.min(end, to);
      if (first >= last) return;
      hidden.set(first - start, last - start);
      if (privateKey) privateKeys.set(first - start, last - start);
    }

    String render(String source) {
      StringBuilder safe = new StringBuilder(end - start);
      int position = 0;
      for (int first = hidden.nextSetBit(0); first >= 0; first = hidden.nextSetBit(position)) {
        safe.append(source, start + position, start + first);
        position = hidden.nextClearBit(first);
        int key = privateKeys.nextSetBit(first);
        safe.append(key >= 0 && key < position ? UiText.text("[私钥已隐藏]") : "***");
      }
      return safe.append(source, start + position, end).toString();
    }
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
