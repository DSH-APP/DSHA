package com.deepseekharness.app.util;

/**
 * 虚拟屏写操作的响应契约：把「执行前被拒（动作没有落地）」与「执行后目标/运行状态已变（动作可能已经落地）」
 * 分开。后者不再复用 {@code SCREEN_TARGET_CHANGED} —— 它保留动作的真实结果、只追加标记，调用方仅凭响应就能
 * 判断动作是否已经落地（带标记的结果不得当成失败直接重试），不必靠日志或猜测。
 */
public final class ScreenActionResult {
  /** 执行前拒绝：动作确定没有落地，保持历史错误码。 */
  public static final String NOT_EXECUTED_ERROR = "SCREEN_TARGET_CHANGED";

  /**
   * 主屏无障碍路径（读屏 / 点击 / 输入 / 按键 / 手势）遇到「目标窗口或运行状态已经变了」时的稳定回复。抛出点都在动作
   * 之前，因此这个码只代表「没有执行、可以直接重试」；它与 {@code /app/vscreen/*} 共用同一个字面量，但不把 Java
   * 异常类名当用户可见文本透出（#145 评审）。
   */
  public static final String NOT_EXECUTED_REPLY = "[ERR] " + NOT_EXECUTED_ERROR;

  /** 动作已执行，但执行后目标身份（窗口）已变。 */
  public static final String TARGET_CHANGED_AFTER_EXECUTION = "targetChangedAfterExecution";

  /** 动作已执行，但执行后运行代次、授权或停止状态已变。 */
  public static final String RUN_CHANGED_AFTER_EXECUTION = "runChangedAfterExecution";

  private ScreenActionResult() {}

  /** 前置拒绝：动作未执行。 */
  public static String notExecuted() {
    return "{\"ok\":false,\"error\":\"" + NOT_EXECUTED_ERROR + "\"}";
  }

  /**
   * 动作已执行：原样返回动作结果，只在执行后状态确实变了时追加标记。
   *
   * <p>只对顶层 {@code ok=true} 的 JSON 对象生效：动作本身失败（{@code ok=false}）或结果不是对象时原样返回，
   * 绝不给一个没有落地的动作贴上「已执行」标记。标记已存在时不重复追加 —— 管理器层与桥层会各判一次。
   */
  public static String executed(String result, boolean targetChanged, boolean runChanged) {
    if (result == null || (!targetChanged && !runChanged)) return result;
    int ok = topLevelValueStart(result, "ok");
    if (ok < 0 || !result.startsWith("true", ok)) return result;
    StringBuilder marks = new StringBuilder();
    if (targetChanged && topLevelValueStart(result, TARGET_CHANGED_AFTER_EXECUTION) < 0)
      marks.append(",\"").append(TARGET_CHANGED_AFTER_EXECUTION).append("\":true");
    if (runChanged && topLevelValueStart(result, RUN_CHANGED_AFTER_EXECUTION) < 0)
      marks.append(",\"").append(RUN_CHANGED_AFTER_EXECUTION).append("\":true");
    int end = topLevelEnd(result);
    if (marks.length() == 0 || end < 0) return result;
    return result.substring(0, end) + marks + result.substring(end);
  }

  /** 顶层对象里 {@code key} 对应值的起始下标；键不存在、不是单个对象或 JSON 未闭合时返回 -1。 */
  private static int topLevelValueStart(String json, String key) {
    int depth = 0;
    int index = 0;
    while (index < json.length()) {
      char current = json.charAt(index);
      if (current == '"') {
        int close = stringEnd(json, index);
        if (close < 0) return -1;
        if (depth == 1) {
          int colon = skipSpace(json, close + 1);
          if (colon < json.length()
              && json.charAt(colon) == ':'
              && close == index + 1 + key.length()
              && json.regionMatches(index + 1, key, 0, key.length()))
            return skipSpace(json, colon + 1);
        }
        index = close + 1;
        continue;
      }
      if (current == '{' || current == '[') depth++;
      else if (current == '}' || current == ']') depth--;
      index++;
    }
    return -1;
  }

  /** 顶层对象右花括号的下标；结果不是单个对象时返回 -1。 */
  private static int topLevelEnd(String json) {
    int depth = 0;
    for (int index = 0; index < json.length(); index++) {
      char current = json.charAt(index);
      if (current == '"') {
        int close = stringEnd(json, index);
        if (close < 0) return -1;
        index = close;
      } else if (current == '{') {
        depth++;
      } else if (current == '}') {
        depth--;
        if (depth == 0) return index;
      }
    }
    return -1;
  }

  /** 从开引号 {@code start} 找到未被转义的闭引号下标；字符串未闭合返回 -1。 */
  private static int stringEnd(String json, int start) {
    for (int index = start + 1; index < json.length(); index++) {
      char current = json.charAt(index);
      if (current == '\\') {
        index++;
        continue;
      }
      if (current == '"') return index;
    }
    return -1;
  }

  private static int skipSpace(String json, int from) {
    int index = from;
    while (index < json.length() && Character.isWhitespace(json.charAt(index))) index++;
    return index;
  }
}
