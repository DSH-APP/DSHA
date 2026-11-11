package com.deepseekharness.app.util;

import java.util.List;

/**
 * 可从网页兜底卡片复制的有界诊断摘要。
 *
 * <p>只包含固定身份字段、数字与既有脱敏事件行；不拼接异常原文、页面正文、Cookie 或 query。事件条数
 * 与单行长度都有上限，总长度不超过 {@link #LIMIT}，避免剪贴板里出现整段日志。
 */
public final class DiagnosticDigestText {
  private DiagnosticDigestText() {}

  public static final int LIMIT = 6000;
  public static final int MAX_EVENTS = 30;
  public static final int MAX_EVENT_CHARS = 300;

  public static String build(
      String versionName,
      int versionCode,
      String runtimeId,
      String webView,
      long usableBytes,
      String maintenance,
      List<String> events) {
    StringBuilder out = new StringBuilder("DSHA 诊断摘要 / diagnostic summary\n");
    out.append("version=")
        .append(clean(versionName, 80))
        .append(" (")
        .append(versionCode)
        .append(")\n");
    out.append("runtimeId=").append(clean(runtimeId, 120)).append('\n');
    out.append("webview=").append(clean(webView, 160)).append('\n');
    out.append("freeBytes=").append(Math.max(0, usableBytes)).append('\n');
    String state = clean(maintenance, 240);
    out.append("maintenance=").append(state.isEmpty() ? "unknown" : state).append('\n');
    out.append("recentEvents:\n");
    if (events == null || events.isEmpty()) {
      out.append("(none)\n");
    } else {
      int start = Math.max(0, events.size() - MAX_EVENTS);
      for (int index = start; index < events.size(); index++) {
        String line = SensitiveData.redact(events.get(index));
        line = clean(line, MAX_EVENT_CHARS);
        if (!line.isEmpty()) out.append(line).append('\n');
        if (out.length() >= LIMIT) break;
      }
    }
    String value = SensitiveData.redact(out.toString());
    return value.length() <= LIMIT ? value : value.substring(0, LIMIT);
  }

  /** 单行化并限长；控制字符与换行统一替换为空格，防止摘要被多行伪造。 */
  static String clean(String value, int limit) {
    if (value == null) return "";
    StringBuilder out = new StringBuilder(Math.min(value.length(), limit));
    for (int index = 0; index < value.length() && out.length() < limit; index++) {
      char c = value.charAt(index);
      out.append(c < ' ' || c == '\u007f' ? ' ' : c);
    }
    return out.toString().trim();
  }
}
