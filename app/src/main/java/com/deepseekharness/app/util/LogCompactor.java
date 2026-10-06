package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只压缩显示副本；数字、路径和记录顺序具有诊断意义，不作为重复噪声抹掉。 */
public final class LogCompactor {
  private static final Pattern PREFIX =
      Pattern.compile(
          "^(?:\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}|\\[\\d{10,}\\]|\\[\\d+(?:\\.\\d+)?s\\]) ");
  private static final Pattern RECORD = Pattern.compile("(?m)^(?=\\[\\d{10,}\\] [A-Z_]+$)");
  private static final Pattern IMPORTANT =
      Pattern.compile(
          "(?i)(exception|error|failed|failure|fatal|crash|denied|read-only|killed|signal|errno|超时|失败|崩溃|异常|拒绝)|\\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\\b");

  private LogCompactor() {}

  /** 只折叠连续且正文完全相同的行；时间前缀以首末时间和次数保留。 */
  public static String foldRepeats(String text) {
    if (text == null || text.isEmpty()) return "";
    String[] lines = text.split("\n", -1);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < lines.length; ) {
      String body = body(lines[i]);
      int end = i + 1;
      if (body.length() >= 12) while (end < lines.length && body.equals(body(lines[end]))) end++;
      if (i > 0) out.append('\n');
      out.append(lines[i]);
      if (end > i + 1) {
        out.append("  (×").append(end - i);
        String first = stamp(lines[i]), last = stamp(lines[end - 1]);
        if (!first.isEmpty() && !last.isEmpty() && !first.equals(last))
          out.append(", ").append(first).append(" ~ ").append(last);
        out.append(')');
      }
      i = end;
    }
    return out.toString();
  }

  private static String body(String line) {
    return PREFIX.matcher(line).replaceFirst("");
  }

  private static String stamp(String line) {
    Matcher match = PREFIX.matcher(line);
    return match.find() ? match.group().trim() : "";
  }

  public static String headTail(String text, int head, int tail) {
    if (text == null) return "";
    String[] lines = text.split("\n", -1);
    if (lines.length <= head + tail + 2) return text;
    List<String> kept = new ArrayList<>();
    boolean omitted = false;
    for (int i = 0; i < lines.length; i++) {
      if (i < head || i >= lines.length - tail || IMPORTANT.matcher(lines[i]).find()) {
        if (omitted) {
          kept.add(marker());
          omitted = false;
        }
        kept.add(lines[i]);
      } else omitted = true;
    }
    if (omitted) kept.add(marker());
    return String.join("\n", kept);
  }

  /** 先比较完整原文再缩短；不同正文不能因头尾相同被合为一次。 */
  public static String compactRecords(String text, int head, int tail) {
    if (text == null || text.isEmpty()) return "";
    String[] records = RECORD.split(text);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < records.length; ) {
      String record = records[i];
      if (!record.startsWith("[")) {
        out.append(headTail(record, head, tail));
        i++;
        continue;
      }
      int end = i + 1;
      while (end < records.length && body(record).equals(body(records[end]))) end++;
      int nl = record.indexOf('\n');
      String heading = nl < 0 ? record : record.substring(0, nl);
      out.append(heading);
      if (end > i + 1)
        out.append("  (×")
            .append(end - i)
            .append(", ")
            .append(stamp(record))
            .append(" ~ ")
            .append(stamp(records[end - 1]))
            .append(')');
      out.append('\n').append(headTail(nl < 0 ? "" : record.substring(nl + 1), head, tail));
      if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
      i = end;
    }
    return out.toString();
  }

  /** 真正包含标记的字符上限；超长单行也保留行尾，避免丢掉错误路径。 */
  private static String bound(String text, int maxChars) {
    if (maxChars <= 0) return "";
    if (text.length() <= maxChars) return text;
    String mark = "\n" + marker() + "\n";
    if (mark.length() >= maxChars) return prefix("…", maxChars);
    int budget = maxChars - mark.length();
    return prefix(text, budget / 2) + mark + suffix(text, budget - budget / 2);
  }

  private static String prefix(String value, int length) {
    int end = Math.min(length, value.length());
    if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
    return value.substring(0, end);
  }

  private static String suffix(String value, int length) {
    int start = Math.max(0, value.length() - length);
    if (start < value.length() && Character.isLowSurrogate(value.charAt(start))) start++;
    return value.substring(start);
  }

  public static String cap(String text, int maxChars) {
    if (maxChars < 0) throw new IllegalArgumentException("NEGATIVE_LOG_LIMIT");
    if (text == null) return "";
    if (text.length() <= maxChars) return text;
    StringBuilder important = new StringBuilder();
    for (String line : text.split("\n", -1))
      if (IMPORTANT.matcher(line).find()) important.append(line).append('\n');
    String label = UiText.choose("失败线索（显示摘要）：\n", "Failure clues (display summary):\n");
    if (important.length() == 0 || maxChars < 160) return bound(text, maxChars);
    String clues = bound(important.toString(), Math.max(0, maxChars * 2 / 3 - label.length()));
    String context = UiText.choose("\n上下文：\n", "\nContext:\n");
    return label
        + clues
        + context
        + bound(text, maxChars - label.length() - clues.length() - context.length());
  }

  private static String marker() {
    return UiText.choose(
        "… [已省略部分显示内容，原始记录仍保留] …", "… [display shortened; original record retained] …");
  }

  public static String compact(String text, int maxChars) {
    return cap(foldRepeats(text), maxChars);
  }

  public static String compactReason(String text, int maxChars) {
    return cap(headTail(text, 8, 10), maxChars);
  }

  public static String compactInstall(String text, int maxChars) {
    return cap(compactRecords(text, 6, 8), maxChars);
  }
}
