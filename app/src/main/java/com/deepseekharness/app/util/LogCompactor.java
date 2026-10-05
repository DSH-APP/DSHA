package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* 导出错误日志前的压缩：折叠重复行、把长命令输出缩成头尾、给每段设字符上限。
* 纯 Java，不依赖 Android。
*
* 原则：只合并「内容完全相同」的重复，不丢任何独有的信息。
* - 普通行按「抹掉数字」的签名合并（端口号、耗时等易变数字不影响归并）；
* - 含 error / failed / exception 等关键词的行只按原文合并，数字不同就是不同的行；
* - 省略处只写中性标记（×N、omitted），不依赖界面语言，也不进文案目录。
*/
public final class LogCompactor {
  private LogCompactor() { }

  /** 行首形如 "10-05 03:20:08 "（应用操作日志）。 */
  private static final Pattern TS = Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) ");
  /** 行首形如 "[1791141608957] "（冷安装记录）。 */
  private static final Pattern EPOCH = Pattern.compile("^(?:\\[\\d{10,}\\]|\\[\\d+(?:\\.\\d+)?s\\]|\\d{10,} /) ");
  /** 冷安装记录的头：单独成行的 "[毫秒时间戳] 类型"。 */
  private static final Pattern RECORD_HEAD = Pattern.compile("(?m)^(?=\\[\\d{10,}\\] [A-Z_]+$)");
  private static final Pattern DIGITS = Pattern.compile("\\d+");
  /** 这些词出现的行视为失败线索：折叠时数字也必须一致，截取时一律保留。 */
  private static final Pattern IMPORTANT = Pattern.compile(
      "(?i)(exception|(?<![\\w-])error(?![\\w-])|failed|failure|fatal|crash|denied|read-only|killed|signal|超时|失败|崩溃|异常)");

  /** 折叠重复行（相邻或不相邻），保持首次出现的顺序；重复的行在首次出现处标 ×次数。 */
  public static String foldRepeats(String text) {
    if (text == null || text.isEmpty()) return "";
    String[] lines = text.split("\n", -1);
    Map<String, int[]> seen = new LinkedHashMap<>();    // 签名 → {次数, 首行, 末行}
    String[] sig = new String[lines.length];
    for (int i = 0; i < lines.length; i++) {
      String body = TS.matcher(EPOCH.matcher(lines[i]).replaceFirst("")).replaceFirst("");
      if (body.length() < 12) continue;               // 空行、分隔线、短标题不折叠
      String s = IMPORTANT.matcher(body).find() ? "E|" + body : "N|" + DIGITS.matcher(body).replaceAll("#");
      sig[i] = s;
      int[] c = seen.get(s);
      if (c == null) seen.put(s, new int[]{1, i, i});
      else { c[0]++; c[2] = i; }
    }
    StringBuilder out = new StringBuilder(text.length() / 2 + 16);
    boolean first = true;
    for (int i = 0; i < lines.length; i++) {
      int[] c = sig[i] == null ? null : seen.get(sig[i]);
      if (c != null && c[0] > 1 && c[1] != i) continue;       // 重复出现的后续行：丢弃
      if (!first) out.append('\n');
      first = false;
      out.append(lines[i]);
      if (c != null && c[0] > 1) {
        out.append("  (×").append(c[0]);
        String a = time(lines[c[1]]), b = time(lines[c[2]]);
        if (a != null && b != null && !a.equals(b)) out.append(", ").append(a).append(" ~ ").append(b);
        out.append(')');
      }
    }
    return out.toString();
  }

  private static String time(String line) {
    Matcher m = TS.matcher(line);
    return m.find() ? m.group(1) : null;
  }

  /** 一段文本只留开头 head 行、结尾 tail 行，以及中间所有关键词行；其余写明省略行数。 */
  public static String headTail(String text, int head, int tail) {
    if (text == null) return "";
    String[] lines = text.split("\n", -1);
    if (lines.length <= head + tail + 2) return text;
    List<String> keep = new ArrayList<>();
    int skipped = 0;
    for (int i = 0; i < lines.length; i++) {
      boolean keepLine = i < head || i >= lines.length - tail || IMPORTANT.matcher(lines[i]).find();
      if (keepLine) {
        if (skipped > 0) { keep.add("… [" + skipped + " lines omitted]"); skipped = 0; }
        keep.add(lines[i]);
      } else skipped++;
    }
    if (skipped > 0) keep.add("… [" + skipped + " lines omitted]");
    return String.join("\n", keep);
  }

  /**
  * 冷安装一类的多行记录：按 "[毫秒] 类型" 头切条。
  * 1) 每条内部缩成头尾（保留关键词行）；
  * 2) 内容（去掉头与数字后）相同的整条记录合并：保留每次的毫秒时间戳，正文只写一份。
  *    这样三次失败仍能逐次看到「什么时候发生」，但不再重复三遍 dpkg 输出。
  * 失败条目（含关键词）只按原文归并，数字不同就不是同一条。
  */
  public static String compactRecords(String text, int head, int tail) {
    if (text == null || text.isEmpty()) return "";
    String[] records = RECORD_HEAD.split(text);
    Map<String, List<String>> stamps = new LinkedHashMap<>();   // 内容签名 → 各次的头行
    Map<String, String> bodies = new LinkedHashMap<>();
    List<String> order = new ArrayList<>();
    for (String record : records) {
      if (record.trim().isEmpty()) continue;
      int nl = record.indexOf('\n');
      String headLine = (nl < 0 ? record : record.substring(0, nl)).trim();
      String body = nl < 0 ? "" : headTail(record.substring(nl + 1), head, tail);
      String kind = EPOCH.matcher(headLine).replaceFirst("");
      String sig = kind + "|" + (IMPORTANT.matcher(body).find() ? body : DIGITS.matcher(body).replaceAll("#"));
      if (!bodies.containsKey(sig)) { bodies.put(sig, body); order.add(sig); stamps.put(sig, new ArrayList<>()); }
      stamps.get(sig).add(headLine);
    }
    StringBuilder out = new StringBuilder();
    for (String sig : order) {
      List<String> hs = stamps.get(sig);
      out.append('\n').append(hs.get(0));
      if (hs.size() > 1) {
        out.append("  (×").append(hs.size()).append(", also at");
        for (int i = 1; i < hs.size(); i++) out.append(i > 1 ? ", " : " ").append(stampOf(hs.get(i)));
        out.append(')');
      }
      out.append('\n').append(bodies.get(sig));
      if (!bodies.get(sig).endsWith("\n")) out.append('\n');
    }
    return out.toString();
  }

  private static final Pattern STAMP = Pattern.compile("^\\[(\\d{10,})\\]");

  private static String stampOf(String headLine) {
    Matcher m = STAMP.matcher(headLine);
    return m.find() ? m.group(1) : headLine;
  }

  /** 整段不得超过 maxChars；超出时保留开头与结尾，中间写明省略量。 */
  public static String cap(String text, int maxChars) {
    if (text == null) return "";
    if (text.length() <= maxChars) return text;
    int head = maxChars * 2 / 5, tail = maxChars - head;
    int cutHead = text.lastIndexOf('\n', head);
    if (cutHead < 0) cutHead = head;
    int cutTail = text.indexOf('\n', text.length() - tail);
    if (cutTail < 0) cutTail = text.length() - tail;
    if (cutTail <= cutHead) return text.substring(0, maxChars);
    return text.substring(0, cutHead) + "\n… [" + (cutTail - cutHead) + " chars omitted] …\n" + text.substring(cutTail + 1);
  }

  /** 应用操作、启动记录一类：先截短超长行，再折叠重复，最后封顶。 */
  public static String compact(String text, int maxChars) {
    return cap(foldRepeats(clipLines(text, 160)), maxChars);
  }

  /**
  * 超长的单行只留前 maxLine 个字符，后面写明省略量。
  * 含失败关键词的行不截：报错正文和路径往往就在行尾。
  */
  public static String clipLines(String text, int maxLine) {
    if (text == null || text.isEmpty()) return "";
    String[] lines = text.split("\n", -1);
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < lines.length; i++) {
      String l = lines[i];
      if (l.length() > maxLine && !IMPORTANT.matcher(l).find()) {
        int cut = maxLine;
        if (Character.isHighSurrogate(l.charAt(cut - 1))) cut--;
        l = l.substring(0, cut) + "… [+" + (lines[i].length() - cut) + " chars]";
      }
      out.append(l);
      if (i < lines.length - 1) out.append('\n');
    }
    return out.toString();
  }

  /** 两段文本去掉首尾空白后完全相同，说明是同一份内容被拷贝了两次。 */
  public static boolean sameContent(String a, String b) {
    return a != null && b != null && !a.trim().isEmpty() && a.trim().equals(b.trim());
  }

  /** 失败原因这类「一个字段里塞了整段命令输出」的文本：保留关键词行与头尾，上限 maxChars。 */
  public static String compactReason(String text, int maxChars) {
    return cap(headTail(text, 8, 10), maxChars);
  }

  /** 冷安装一类（多行记录）：按整条记录去重并缩成头尾，最后封顶。不再做逐行折叠，避免把不同次的记录混在一起。 */
  public static String compactInstall(String text, int maxChars) {
    return cap(compactRecords(text, 6, 8), maxChars);
  }
}
