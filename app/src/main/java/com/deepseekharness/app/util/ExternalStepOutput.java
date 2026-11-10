package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外接 ADB 多步会话的输出格式与解析（同一个专属 nodaemon server 内顺序执行多条客户端命令）。
 *
 * <p>每步输出前写一行 {@code [DSHA_STEP <nonce> <序号>]}，步骤本身仍以 {@code [EXIT=n]} 或
 * {@code [EXECUTION_UNKNOWN]} 结束。nonce 由宿主每次随机生成，目标机输出无法伪造步骤边界。
 *
 * <p>纯逻辑，配单测。
 */
public final class ExternalStepOutput {

  private ExternalStepOutput() {}

  private static final Pattern NONCE = Pattern.compile("[0-9a-f]{16,64}");
  private static final Pattern EXIT = Pattern.compile("\\[EXIT=(-?\\d{1,4})\\]\\s*$");

  /** 单步结果。{@code exit} 未知时为 -1 且 {@code unknown=true}，不得重放。 */
  public record Step(int index, String body, int exit, boolean unknown) {
    public boolean ok() {
      return !unknown && exit == 0;
    }
  }

  public static boolean validNonce(String nonce) {
    return nonce != null && NONCE.matcher(nonce).matches();
  }

  public static String marker(String nonce, int index) {
    return "[DSHA_STEP " + nonce + " " + index + "]\n";
  }

  /**
   * 解析整段输出。结果按出现顺序返回；会话在第一步之前就失败（例如 server 未就绪、策略拦截）时返回空列表，调用方用
   * {@link #preamble} 取原因。
   */
  public static List<Step> parse(String output, String nonce) {
    List<Step> steps = new ArrayList<>();
    if (output == null || !validNonce(nonce)) return steps;
    Pattern marker = Pattern.compile("\\[DSHA_STEP " + nonce + " (\\d{1,3})\\]\\n");
    Matcher m = marker.matcher(output);
    List<int[]> bounds = new ArrayList<>();
    while (m.find()) bounds.add(new int[] {m.start(), m.end(), Integer.parseInt(m.group(1))});
    for (int i = 0; i < bounds.size(); i++) {
      int end = i + 1 < bounds.size() ? bounds.get(i + 1)[0] : output.length();
      String segment = output.substring(bounds.get(i)[1], end);
      steps.add(step(bounds.get(i)[2], segment));
    }
    return steps;
  }

  /** 第一个步骤标记之前的输出（会话级错误）。 */
  public static String preamble(String output, String nonce) {
    if (output == null) return "";
    if (!validNonce(nonce)) return output;
    int at = output.indexOf("[DSHA_STEP " + nonce + " ");
    return at < 0 ? output : output.substring(0, at);
  }

  private static Step step(int index, String segment) {
    String trimmed = segment.endsWith("\n") ? segment.substring(0, segment.length() - 1) : segment;
    if (trimmed.contains("[EXECUTION_UNKNOWN]")) return new Step(index, trimmed, -1, true);
    Matcher exit = EXIT.matcher(trimmed);
    if (!exit.find()) return new Step(index, trimmed, -1, true);
    String body = trimmed.substring(0, exit.start());
    if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
    return new Step(index, body, Integer.parseInt(exit.group(1)), false);
  }

  /** {@code adb connect} 的退出码在部分版本上恒为 0，必须再看输出。 */
  public static boolean connected(String body) {
    if (body == null) return false;
    String lower = body.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("failed") || lower.contains("cannot") || lower.contains("unable"))
      return false;
    return lower.contains("connected to");
  }

  /** {@code adb pair} 成功输出 {@code Successfully paired to ...}。 */
  public static boolean paired(String body) {
    return body != null && body.contains("Successfully paired to");
  }
}
