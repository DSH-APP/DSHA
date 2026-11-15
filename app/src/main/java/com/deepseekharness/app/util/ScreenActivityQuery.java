package com.deepseekharness.app.util;

/**
 * {@code /app/ui/history} 的查询参数规则：{@code since} 是事件序号（{@code nextSince}），
 * {@code limit} 默认 20、上限 100。参数非法时抛带稳定错误码的 {@link IllegalArgumentException}，
 * 由调用方转成未执行的拒绝回执，不猜测、不修正用户输入。
 */
public final class ScreenActivityQuery {
  private ScreenActivityQuery() {}

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;
  public static final long MAX_SINCE = 9_007_199_254_740_991L;

  public record Parsed(long since, int limit) {}

  public static Parsed parse(String since, String limit) {
    return new Parsed(parseSince(since), parseLimit(limit));
  }

  private static long parseSince(String value) {
    String text = value == null ? "" : value.trim();
    if (text.isEmpty()) return 0L;
    if (!text.matches("[0-9]{1,19}")) throw invalid("SCREEN_HISTORY_INVALID_SINCE");
    try {
      long parsed = Long.parseLong(text);
      if (parsed > MAX_SINCE) throw invalid("SCREEN_HISTORY_INVALID_SINCE");
      return parsed;
    } catch (NumberFormatException overflow) {
      throw invalid("SCREEN_HISTORY_INVALID_SINCE");
    }
  }

  private static int parseLimit(String value) {
    String text = value == null ? "" : value.trim();
    if (text.isEmpty()) return DEFAULT_LIMIT;
    if (!text.matches("[0-9]{1,3}")) throw invalid("SCREEN_HISTORY_INVALID_LIMIT");
    int parsed;
    try {
      parsed = Integer.parseInt(text);
    } catch (NumberFormatException overflow) {
      throw invalid("SCREEN_HISTORY_INVALID_LIMIT");
    }
    if (parsed < 1 || parsed > MAX_LIMIT) throw invalid("SCREEN_HISTORY_INVALID_LIMIT");
    return parsed;
  }

  private static IllegalArgumentException invalid(String code) {
    return new IllegalArgumentException(code);
  }
}
