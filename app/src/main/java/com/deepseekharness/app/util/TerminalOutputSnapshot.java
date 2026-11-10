package com.deepseekharness.app.util;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;

/** 主线程读取终端缓冲区得到的只读快照；后续输出和会话切换不会改变导出内容。 */
public final class TerminalOutputSnapshot {
  public static final int MAX_CHARACTERS = 65536;
  private static final int MAX_ROWS = 10000;
  // 上下文预算独立于导出预算；超过预算时不使用缺少上下文的尾部代替。
  static final int MAX_CONTEXT_CHARACTERS = 16 * MAX_CHARACTERS;

  public record Part(String text, boolean truncated) {}

  public final String sessionName;
  public final Part screen;
  public final Part history;

  private TerminalOutputSnapshot(String sessionName, Part screen, Part history) {
    this.sessionName = sessionName;
    this.screen = screen;
    this.history = history;
  }

  /** 必须与 emulator 的更新在同一线程调用；当前屏幕遵循用户实际滚动到的位置。 */
  public static TerminalOutputSnapshot capture(
      String sessionName, TerminalEmulator emulator, int topRow) {
    TerminalBuffer buffer = emulator.getScreen();
    int first = -buffer.getActiveTranscriptRows();
    int top = Math.max(first, Math.min(0, topRow));
    int last = emulator.mRows - 1;
    Source source = readContext(buffer, emulator.mColumns, first, last);
    if (source == null) {
      Part withheld = new Part("", true);
      return new TerminalOutputSnapshot(SensitiveData.redact(sessionName), withheld, withheld);
    }
    Selection screen =
        select(source, top - first, Math.min(last, top + emulator.mRows - 1) - first);
    Selection history = select(source, 0, last - first);
    // 在完整上下文中识别敏感区间，再投影到原始行范围；替换变短也不引入范围外正文。
    String[] safe =
        SensitiveData.redactRanges(
            source.text(), screen.start(), screen.end(), history.start(), history.end());
    return new TerminalOutputSnapshot(
        SensitiveData.redact(sessionName),
        finish(safe[0], screen.truncated()),
        finish(safe[1], history.truncated()));
  }

  private record Source(String text, int[] starts, boolean[] wrapped) {}

  private record Selection(int start, int end, boolean truncated) {}

  private static Source readContext(TerminalBuffer buffer, int columns, int first, int last) {
    int count = last - first + 1;
    int[] starts = new int[count + 1];
    boolean[] wrapped = new boolean[count];
    StringBuilder raw = new StringBuilder();
    for (int index = 0; index < count; index++) {
      int row = first + index;
      String line = buffer.getSelectedText(0, row, columns - 1, row);
      wrapped[index] = buffer.getLineWrap(row);
      boolean newline = row < last && !wrapped[index];
      if (line.length() > MAX_CONTEXT_CHARACTERS - raw.length() - (newline ? 1 : 0)) return null;
      starts[index] = raw.length();
      raw.append(line);
      if (newline) raw.append('\n');
    }
    starts[count] = raw.length();
    return new Source(raw.toString(), starts, wrapped);
  }

  private static Selection select(Source source, int first, int last) {
    int end = source.starts()[last + 1];
    if (last + 1 < source.wrapped().length && !source.wrapped()[last]) end--;
    for (int row = last; row >= first; row--) {
      if (last - row >= MAX_ROWS || end - source.starts()[row] > MAX_CHARACTERS) {
        int startRow = row + 1;
        // 保留既有尾部范围：截断点若在软换行中，舍弃不完整逻辑行。
        if (source.wrapped()[row]) {
          while (startRow <= last) {
            if (!source.wrapped()[startRow++]) break;
          }
        }
        return new Selection(startRow > last ? end : source.starts()[startRow], end, true);
      }
    }
    return new Selection(source.starts()[first], end, false);
  }

  private static Part finish(String text, boolean truncated) {
    // 短敏感值替换为 *** 可能变长；最后的预算以真正展示/导出的字符串为准。
    if (text.length() > MAX_CHARACTERS) {
      int start = text.length() - MAX_CHARACTERS;
      if (Character.isLowSurrogate(text.charAt(start))) start++;
      text = text.substring(start);
      truncated = true;
    }
    return new Part(text, truncated);
  }
}
