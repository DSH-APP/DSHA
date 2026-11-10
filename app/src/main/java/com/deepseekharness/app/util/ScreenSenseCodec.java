package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** 紧凑、可读、无 Android 依赖的识屏文本编码。 */
public final class ScreenSenseCodec {
  private ScreenSenseCodec() {}

  public static String encode(ScreenSenseState state) {
    StringBuilder result =
        new StringBuilder()
            .append('S')
            .append(state.sid)
            .append(' ')
            .append(state.target.packageName)
            .append(' ')
            .append(state.width)
            .append('x')
            .append(state.height)
            .append(" q=")
            .append(state.quality())
            .append(" f=")
            .append(state.focused ? '1' : '0')
            .append(" ime=")
            .append(state.ime < 0 ? "?" : Integer.toString(state.ime))
            .append(" src=")
            .append(state.source)
            .append(" v=")
            .append(state.visited);
    if (state.truncated && !state.truncationReason.isEmpty())
      result.append(" why=").append(state.truncationReason);
    result.append('\n');
    for (ScreenSenseState.Element element : state.elements) appendElement(result, "", element);
    return result.toString();
  }

  public static Delta diff(ScreenSenseState previous, ScreenSenseState current) {
    if (previous == null || current == null)
      throw new IllegalArgumentException("SCREEN_SENSE_DIFF_INVALID");
    if (!previous.treeId.equals(current.treeId)
        || !previous.target.matches(current.target)
        || current.sid <= previous.sid)
      throw new IllegalArgumentException("SCREEN_SENSE_SCOPE_MISMATCH");
    if (previous.width != current.width
        || previous.height != current.height
        || previous.focused != current.focused
        || previous.ime != current.ime
        || !previous.source.equals(current.source)
        || previous.truncated != current.truncated
        || !previous.truncationReason.equals(current.truncationReason))
      throw new IllegalArgumentException("SCREEN_SENSE_FULL_REQUIRED");
    Map<String, ScreenSenseState.Element> before = byId(previous);
    Map<String, ScreenSenseState.Element> after = byId(current);
    int changes = 0;
    StringBuilder lines = new StringBuilder();
    for (String id : before.keySet()) {
      if (!after.containsKey(id)) {
        changes++;
        lines.append('-').append(id).append('\n');
      }
    }
    for (ScreenSenseState.Element element : current.elements) {
      ScreenSenseState.Element old = before.get(element.id);
      if (old == null) {
        changes++;
        appendElement(lines, "+", element);
      } else if (!old.equals(element)) {
        changes++;
        appendElement(lines, "~", element);
      }
    }
    return new Delta(previous.sid, current.sid, changes, lines.toString());
  }

  public static String encodeDelta(ScreenSenseState previous, ScreenSenseState current) {
    return encodeDelta(diff(previous, current), current);
  }

  /** 已算好的差异直接编码，避免连续识屏时重复比较两棵树。 */
  public static String encodeDelta(Delta delta, ScreenSenseState current) {
    if (delta == null || current == null)
      throw new IllegalArgumentException("SCREEN_SENSE_DIFF_INVALID");
    StringBuilder result =
        new StringBuilder()
            .append("D ")
            .append(delta.sid)
            .append(' ')
            .append(delta.previousSid)
            .append(' ')
            .append(delta.changedCount)
            .append(' ')
            .append(current.width)
            .append('x')
            .append(current.height)
            .append(" q=")
            .append(current.quality())
            .append(" f=")
            .append(current.focused ? '1' : '0')
            .append(" ime=")
            .append(current.ime < 0 ? "?" : Integer.toString(current.ime))
            .append(" src=")
            .append(current.source)
            .append(" v=")
            .append(current.visited)
            .append('\n');
    return result.append(delta.lines).toString();
  }

  public static final class Delta {
    public final long previousSid, sid;
    public final int changedCount;
    public final String lines;

    private Delta(long previousSid, long sid, int changedCount, String lines) {
      this.previousSid = previousSid;
      this.sid = sid;
      this.changedCount = changedCount;
      this.lines = lines;
    }
  }

  private static Map<String, ScreenSenseState.Element> byId(ScreenSenseState state) {
    Map<String, ScreenSenseState.Element> result = new LinkedHashMap<>();
    for (ScreenSenseState.Element element : state.elements)
      if (result.put(element.id, element) != null)
        throw new IllegalArgumentException("SCREEN_SENSE_DUPLICATE_ID");
    return result;
  }

  private static void appendElement(
      StringBuilder result, String prefix, ScreenSenseState.Element element) {
    result
        .append(prefix)
        .append(element.id)
        .append(' ')
        .append(element.role)
        .append(" \"")
        .append(escape(element.label.isEmpty() ? "?" : element.label))
        .append('"');
    // flags（!*%^~）与 actions（clfbot）字符集互不相交，合成一个 token 仍可无歧义拆分；
    // 两者都为空时整段省略，纯文本元素不再带占位符。
    if (!element.flags.isEmpty() || !element.actions.isEmpty())
      result.append(' ').append(element.flags).append(element.actions);
    if (element.hasBounds)
      result
          .append(" @")
          .append(element.left)
          .append(',')
          .append(element.top)
          .append(',')
          .append(element.right)
          .append(',')
          .append(element.bottom);
    result.append('\n');
  }

  private static String escape(String value) {
    StringBuilder result = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '\\' -> result.append("\\\\");
        case '"' -> result.append("\\\"");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        case '\b' -> result.append("\\b");
        case '\f' -> result.append("\\f");
        default -> {
          if (ch < 0x20) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
          else result.append(ch);
        }
      }
    }
    return result.toString();
  }
}
