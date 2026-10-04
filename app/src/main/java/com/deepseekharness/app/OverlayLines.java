package com.deepseekharness.app;

/**
 * 悬浮条的分行与滚动（纯逻辑、不碰 Android API —— 断言在 tools/pure-logic-test.sh）。
 *
 * <p><b>为什么要有这个类</b>：悬浮条原先是「一整段文本 + 取最后 N 个字符 + 交给系统折行」。
 * 流式输出每来一个字，这段文本的尾部窗口就右移一格，折行点跟着变，于是<b>每一行的内容
 * 都在变</b> —— 看起来就是文字不停往左滚走，根本读不了。用户原话：可读性很差。
 *
 * <p>改成先按显示宽度把文本切成固定的行，再取最后若干行：
 * <ul>
 *   <li>切分只依赖文本前缀，所以同一段文本每次切出来的前面几行完全一样 ——
 *       已经显示出来的行定住不动；</li>
 *   <li>新内容只在最后一行长；写满一行就换下一行；</li>
 *   <li>行数到上限时丢掉最旧的一行，其余整体上移一位。</li>
 * </ul>
 *
 * <p>宽度按半角计：中日韩文字与全角标点算 2 格，其余算 1 格 —— 手机上一个汉字确实约等于
 * 两个 ASCII 字符宽。如果按「字符数」算，30 个汉字会挤成两三行，系统再折一次就超出设定
 * 行数，被 maxLines 截掉，看起来像丢字。
 */
final class OverlayLines {

  private OverlayLines() {}

  /** 一行放多少个半角宽度。13sp 字号下手机窄屏大约就是这个数，宁可窄一点也别让系统再折。 */
  static final int DEFAULT_WIDTH = 30;

  /** 一个字符占几格显示宽度（CJK / 全角 = 2，其余 = 1）。 */
  static int charWidth(char c) {
    if (c < 0x1100) return 1;
    if (c <= 0x115F) return 2; // 韩文字母
    if (c == 0x2329 || c == 0x232A) return 2; // 〈 〉
    if (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F) return 2; // CJK 部首 → 彝文
    if (c >= 0xAC00 && c <= 0xD7A3) return 2; // 韩文音节
    if (c >= 0xF900 && c <= 0xFAFF) return 2; // CJK 兼容表意
    if (c >= 0xFE30 && c <= 0xFE6F) return 2; // CJK 兼容形式
    if (c >= 0xFF00 && c <= 0xFF60) return 2; // 全角 ASCII
    if (c >= 0xFFE0 && c <= 0xFFE6) return 2; // 全角符号
    return 1;
  }

  /** 一段文本占几格显示宽度。 */
  static int width(String s) {
    if (s == null) return 0;
    int w = 0;
    for (int i = 0; i < s.length(); i++) w += charWidth(s.charAt(i));
    return w;
  }

  /**
   * 按显示宽度把文本切成行。含 {@code \n} 时先按 {@code \n} 分段，每段再切。
   *
   * <p>断点优先取空格（英文不至于把单词劈开），但只在空格离行尾不远时才用 ——
   * 否则中文里偶尔出现的一个空格会让整行只用一半宽度。
   */
  static java.util.List<String> wrap(String s, int width) {
    java.util.List<String> out = new java.util.ArrayList<>();
    if (s == null || s.isEmpty()) return out;
    int w = width < 4 ? 4 : width;
    for (String para : s.split("\n", -1)) {
      wrapOne(para, w, out);
    }
    return out;
  }

  private static void wrapOne(String s, int width, java.util.List<String> out) {
    if (s == null) return;
    String t = s.trim();
    if (t.isEmpty()) return;
    int n = t.length();
    int start = 0;
    int w = 0;
    int lastSpace = -1;
    int i = 0;
    while (i < n) {
      char c = t.charAt(i);
      int cw = charWidth(c);
      if (w + cw > width) {
        int cut = i;
        // 空格在行尾三分之一以内才回退到它，否则宁可硬断
        if (lastSpace > start && (i - lastSpace) * 1.0 <= Math.max(4, width / 3.0)) {
          cut = lastSpace;
        }
        if (cut <= start) cut = start + 1; // 保证前进，绝不空转
        out.add(t.substring(start, cut).trim());
        start = cut;
        while (start < n && t.charAt(start) == ' ') start++;
        i = start;
        w = 0;
        lastSpace = -1;
        continue;
      }
      if (c == ' ') lastSpace = i;
      w += cw;
      i++;
    }
    if (start < n) {
      String rest = t.substring(start).trim();
      if (!rest.isEmpty()) out.add(rest);
    }
  }

  /**
   * 取最后 {@code maxLines} 行，用 {@code \n} 拼成可直接 setText 的字符串。
   * 行数没满就全给；满了就丢最旧的，其余上移。
   */
  static String lastLines(String s, int maxLines, int width) {
    int max = maxLines < 1 ? 1 : maxLines;
    java.util.List<String> ls = wrap(s, width);
    if (ls.isEmpty()) return "";
    int from = ls.size() - max;
    if (from < 0) from = 0;
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < ls.size(); i++) {
      if (sb.length() > 0) sb.append('\n');
      sb.append(ls.get(i));
    }
    return sb.toString();
  }

  /**
   * 取<b>开头</b> {@code maxLines} 行，超出时末尾补一个省略号。
   *
   * <p>危险命令确认用这个而不是 {@link #lastLines}：{@code rm -rf} 这种决定「能不能跑」
   * 的部分在最前面，把开头滚掉只留尾巴等于让用户瞎批。
   */
  static String firstLines(String s, int maxLines, int width) {
    int max = maxLines < 1 ? 1 : maxLines;
    java.util.List<String> ls = wrap(s, width);
    if (ls.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < ls.size() && i < max; i++) {
      if (sb.length() > 0) sb.append('\n');
      sb.append(ls.get(i));
    }
    if (ls.size() > max) sb.append(" …");
    return sb.toString();
  }
}
