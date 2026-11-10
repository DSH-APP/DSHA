package com.deepseekharness.app.util;

/**
 * UI v5 方案 A：把一段功能说明拆成「行内一句摘要」与「完整说明」。
 *
 * <p>输入是已经按当前界面语言渲染好的文案，所以这里只认通用句末标点，不按语言写规则：
 *
 * <ul>
 *   <li>中日文句号/问号/叹号（{@code 。！？}）与阿拉伯问号（{@code ؟}）直接断句；
 *   <li>ASCII {@code . ! ?} 只有后面跟空白时才断句，避免把 {@code v1.2}、{@code 127.0.0.1} 截断；
 *   <li>换行也是断句点。
 * </ul>
 *
 * <p>摘要保留句末标点；剩余部分非空白时 {@link Split#hasMore} 为真，界面据此决定是否显示 ⓘ。
 * 纯逻辑，不 import Android API。
 */
public final class SummaryText {
  private SummaryText() {}

  /** 拆分结果；{@code full} 为去除首尾空白后的原文。 */
  public static final class Split {
    public final String summary;
    public final String full;
    public final boolean hasMore;

    Split(String summary, String full, boolean hasMore) {
      this.summary = summary;
      this.full = full;
      this.hasMore = hasMore;
    }
  }

  public static Split split(String text) {
    String full = text == null ? "" : text.trim();
    int end = firstSentenceEnd(full);
    if (end < 0 || end >= full.length()) return new Split(full, full, false);
    String summary = full.substring(0, end).trim();
    String rest = full.substring(end).trim();
    if (summary.isEmpty() || rest.isEmpty()) return new Split(full, full, false);
    return new Split(summary, full, true);
  }

  /** 返回第一句结束后的下标（不含），找不到断句点返回 -1。 */
  static int firstSentenceEnd(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n') return i;
      if (c == '。' || c == '！' || c == '？' || c == '؟') return closeQuotes(text, i + 1);
      if ((c == '.' || c == '!' || c == '?')
          && i + 1 < text.length()
          && Character.isWhitespace(text.charAt(i + 1))) return closeQuotes(text, i + 1);
    }
    return -1;
  }

  /** 句末紧跟的右引号/右括号归入第一句，例如「……生效。」 */
  private static int closeQuotes(String text, int index) {
    while (index < text.length() && "」』）)\"”’".indexOf(text.charAt(index)) >= 0) index++;
    return index;
  }
}
