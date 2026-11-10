package com.deepseekharness.app.util;

/**
 * WebView 页面保活/销毁的脱敏诊断行。
 *
 * <p>只输出事件码、原因码、代次、进程号与布尔事实。原因码经白名单过滤：调用方即使误把页面地址或 token 传进来，
 * 也只会留下 {@code unknown}，所以这些行不能反过来用于还原页面地址。
 */
public final class WebPageDiagnosticText {
  private WebPageDiagnosticText() {}

  public static String engine(long pid, long generation) {
    return "pid=" + pid + " generation=" + generation;
  }

  public static String pageDestroy(
      String reason, boolean changingConfig, boolean finishing, long generation) {
    return "reason="
        + reasonCode(reason)
        + " changingConfig="
        + changingConfig
        + " finishing="
        + finishing
        + " generation="
        + generation;
  }

  public static String keepAlive(String action, String reason, long generation, boolean ready) {
    return "action="
        + actionCode(action)
        + " reason="
        + reasonCode(reason)
        + " generation="
        + generation
        + " ready="
        + ready;
  }

  /**
   * 前台恢复探针行：客户端实现标记、触发路径、可见性、Connection 状态/代次、探测结果、耗时与是否重置。
   *
   * <p>所有字符串字段按 {@link #reasonCode} 白名单过滤，数字限制在诊断需要的区间内；该行只描述可枚举事实。
   */
  public static String resume(
      String impl,
      String trigger,
      String visibility,
      String state,
      String probe,
      long elapsedMs,
      boolean reset,
      String stateAfter,
      long generation,
      long generationAfter,
      long hiddenMs,
      boolean inApp,
      boolean ready,
      long pageGeneration) {
    return "impl="
        + reasonCode(impl)
        + " trigger="
        + reasonCode(trigger)
        + " visibility="
        + reasonCode(visibility)
        + " state="
        + reasonCode(state)
        + " probe="
        + reasonCode(probe)
        + " elapsedMs="
        + clamp(elapsedMs, -1, 600000)
        + " reset="
        + reset
        + " stateAfter="
        + reasonCode(stateAfter)
        + " generation="
        + generation
        + " generationAfter="
        + generationAfter
        + " hiddenMs="
        + clamp(hiddenMs, -1, 86400000)
        + " inApp="
        + inApp
        + " ready="
        + ready
        + " page="
        + pageGeneration;
  }

  private static long clamp(long value, long min, long max) {
    return Math.max(min, Math.min(max, value));
  }

  /** 原因码只接受小写字母、数字与连字符，长度不超过 24；其它输入一律记为 unknown。 */
  public static String reasonCode(String value) {
    if (value == null) return "unknown";
    String safe = value.trim().toLowerCase(java.util.Locale.ROOT);
    if (safe.isEmpty() || safe.length() > 24) return "unknown";
    for (int index = 0; index < safe.length(); index++) {
      char c = safe.charAt(index);
      if ((c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-') return "unknown";
    }
    return safe;
  }

  static String actionCode(String value) {
    if ("ATTACH".equals(value) || "DETACH".equals(value) || "RETIRE".equals(value)) return value;
    return "UNKNOWN";
  }
}
