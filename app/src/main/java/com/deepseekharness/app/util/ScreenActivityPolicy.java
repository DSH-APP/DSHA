package com.deepseekharness.app.util;

/**
 * 屏幕活动事件的纯分类与截断规则：不读无障碍、不持有任务状态、不落盘。
 *
 * <p>敏感应用（{@link SensitiveAppPolicy}）只保留目标切换；密码框只留「已输入」；正文统一压成单行并限长，
 * 避免一次点按把整段页面文本带进回执。助手动作用派发窗口（{@link #DISPATCH_WINDOW_MILLIS}）内的无障碍事件
 * 归属到 {@link ScreenActivityEvent.Source#ASSISTANT}，窗口外的同类事件才记成用户操作。
 */
public final class ScreenActivityPolicy {
  private ScreenActivityPolicy() {}

  /** 事件摘要标签的最大码点数（含省略号）。 */
  public static final int LABEL_MAX = 40;

  /** 控件类名最大码点数；只用于区分控件种类，不保存层级。 */
  public static final int CLASS_MAX = 80;

  /** 助手派发后，这段时间内到达的无障碍事件算助手动作的后果。 */
  public static final long DISPATCH_WINDOW_MILLIS = 1_500L;

  /** 内容变化事件的最小记录间隔；避免滚动或动画把环形缓冲刷满。 */
  public static final long CONTENT_CHANGE_MIN_INTERVAL_MILLIS = 300L;

  /** 同一目标（包名 + Activity）的重复窗口事件去重窗口。 */
  public static final long TARGET_CHANGE_DEDUPE_MILLIS = 500L;

  /** 密码框只记录「已输入」，不记录字符数与内容。 */
  public static final String PASSWORD_LABEL = "已输入";

  /** 无障碍事件里与活动历史有关的部分；其余类型直接忽略。 */
  public enum EventType {
    WINDOW_STATE_CHANGED,
    VIEW_CLICKED,
    VIEW_SCROLLED,
    WINDOW_CONTENT_CHANGED
  }

  public static ScreenActivityEvent.Kind kind(EventType type) {
    if (type == null) return null;
    return switch (type) {
      case WINDOW_STATE_CHANGED -> ScreenActivityEvent.Kind.TARGET_CHANGED;
      case VIEW_CLICKED -> ScreenActivityEvent.Kind.USER_TAP;
      case VIEW_SCROLLED -> ScreenActivityEvent.Kind.USER_SCROLL;
      case WINDOW_CONTENT_CHANGED -> ScreenActivityEvent.Kind.CONTENT_CHANGED;
    };
  }

  public static ScreenActivityEvent.Source source(boolean inDispatchWindow) {
    return inDispatchWindow
        ? ScreenActivityEvent.Source.ASSISTANT
        : ScreenActivityEvent.Source.USER;
  }

  /** 敏感应用只允许目标切换；其余事件（包括助手动作）一律不记。 */
  public static boolean recordable(ScreenActivityEvent.Kind kind, boolean sensitiveApp) {
    return kind != null && (!sensitiveApp || kind == ScreenActivityEvent.Kind.TARGET_CHANGED);
  }

  /** 节点摘要：优先文字，其次描述；密码框固定「已输入」；压成单行并截断。 */
  public static String label(CharSequence text, CharSequence description, boolean password) {
    if (password) return PASSWORD_LABEL;
    CharSequence value = text == null || text.length() == 0 ? description : text;
    return compact(value, LABEL_MAX);
  }

  public static String nodeClass(CharSequence className) {
    return compact(className, CLASS_MAX);
  }

  /** 窗口切换只保留 Activity 短名；包名本身已经单独记录。 */
  public static String activity(CharSequence className) {
    String value = compact(className, CLASS_MAX);
    int dot = value.lastIndexOf('.');
    if (dot >= 0 && dot + 1 < value.length()) value = value.substring(dot + 1);
    return truncate(value, CLASS_MAX);
  }

  public static boolean contentChangeAllowed(long now, long lastRecordedAt) {
    return lastRecordedAt < 0 || now - lastRecordedAt >= CONTENT_CHANGE_MIN_INTERVAL_MILLIS;
  }

  /** 同一包名与 Activity 的连续窗口事件只留一条，减少转场期间的重复目标切换。 */
  public static boolean targetChangeAllowed(
      long now,
      long lastRecordedAt,
      String lastPackage,
      String lastActivity,
      String packageName,
      String activity) {
    if (lastRecordedAt < 0 || now - lastRecordedAt >= TARGET_CHANGE_DEDUPE_MILLIS) return true;
    return !safe(lastPackage).equals(safe(packageName))
        || !safe(lastActivity).equals(safe(activity));
  }

  /**
   * 压成单行：空白与控制字符归一为一个空格，去掉首尾空白，按码点截断到 {@code max} 个码点（含省略号）。
   */
  public static String compact(CharSequence value, int max) {
    if (value == null || value.length() == 0 || max <= 0) return "";
    StringBuilder normalized = new StringBuilder(Math.min(value.length(), max * 2));
    boolean pendingSpace = false;
    for (int index = 0; index < value.length(); ) {
      int codePoint = Character.codePointAt(value, index);
      index += Character.charCount(codePoint);
      if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
        if (normalized.length() > 0) pendingSpace = true;
        continue;
      }
      if (pendingSpace) {
        normalized.append(' ');
        pendingSpace = false;
      }
      normalized.appendCodePoint(codePoint);
    }
    return truncate(normalized.toString(), max);
  }

  private static String truncate(String value, int max) {
    if (value.codePointCount(0, value.length()) <= max) return value;
    int keep = Math.max(0, max - 1);
    int end = value.offsetByCodePoints(0, keep);
    return value.substring(0, end) + "…";
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
