package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把只读 {@code gh api -i /user} 的输出分类为页面可展示的状态。
 *
 * <p>纯逻辑：不做 IO、不读 token。输出只在内存中匹配失败原因与响应头；返回结果只含分类、
 * 作用域、脱敏且长度受限的错误行，不含响应正文、token 或完整 URL。这样页面能区分
 * 「二进制存在 / CA 失效（TLS）/ token 无效 / hosts.yml 未登录」，而不是把一切网络失败都
 * 说成「未登录」。
 */
public final class GhAuthProbe {
  public enum State {
    /** 在线校验通过；token 可用。 */
    VALID,
    /** hosts.yml 里没有可用登录（gh 未登录）。 */
    NOT_LOGGED_IN,
    /** 有登录记录，但 GitHub 返回 401 / Bad credentials。 */
    TOKEN_INVALID,
    /** TLS 证书校验失败；本机 gh CA 路径问题（T73 领域）。 */
    TLS_CA_FAILURE,
    /** DNS / 连接 / 代理失败。 */
    NETWORK_ERROR,
    /** 受管 gh 缺失或损坏；未执行自举。 */
    BINARY_UNAVAILABLE,
    /** 校验超时；不自动重放。 */
    TIMEOUT,
    /** 其他失败；detail 是脱敏后的第一条可行动错误。 */
    ERROR,
    /** 运行环境未就绪，未执行校验。 */
    SKIPPED_ENV
  }

  /** 不可变结果；detail 已脱敏、去 URL 查询串并限制长度。 */
  public static final class Result {
    public final State state;
    public final List<String> scopes;
    public final String detail;

    private Result(State state, List<String> scopes, String detail) {
      this.state = state;
      this.scopes = List.copyOf(scopes);
      this.detail = detail == null ? "" : detail;
    }

    public static Result skippedEnvironment() {
      return new Result(State.SKIPPED_ENV, List.of(), "");
    }

    public static Result binaryUnavailable() {
      return new Result(State.BINARY_UNAVAILABLE, List.of(), "");
    }

    public static Result error(String detail) {
      return new Result(State.ERROR, List.of(), detail == null ? "" : detail.strip());
    }

    @Override
    public String toString() {
      return "GhAuthProbe{state=" + state + ", scopes=" + scopes + ", detail=" + detail + "}";
    }
  }

  /** 错误行展示上限；gh 的 x509/dial 错误通常一行就够。 */
  public static final int MAX_DETAIL = 200;

  private GhAuthProbe() {}

  /**
   * 分类一次校验结果。
   *
   * @param exitCode gh 进程退出码
   * @param timedOut 是否超时被终止
   * @param output 合并后的 stdout/stderr（有界）
   */
  public static Result classify(int exitCode, boolean timedOut, String output) {
    String text = output == null ? "" : output;
    String normalized = text.toLowerCase(Locale.ROOT);
    if (timedOut) return new Result(State.TIMEOUT, List.of(), "");
    if (normalized.contains("managed github cli is missing or damaged")
        || normalized.contains("gh: managed github cli is unavailable"))
      return new Result(State.BINARY_UNAVAILABLE, List.of(), "");
    String tls =
        firstLine(
            text, "certificate signed by unknown authority", "x509:", "tls: failed to verify");
    if (tls != null) return new Result(State.TLS_CA_FAILURE, List.of(), sanitize(tls));
    String invalid =
        firstLine(text, "bad credentials", "http 401", "failed to log in", "is invalid");
    if (invalid != null) return new Result(State.TOKEN_INVALID, List.of(), sanitize(invalid));
    String notLogged =
        firstLine(
            text,
            "not logged in to any hosts",
            "you are not logged in",
            "to get started with github cli");
    if (notLogged != null) return new Result(State.NOT_LOGGED_IN, List.of(), "");
    if (exitCode == 0 && normalized.contains("http/")) {
      return new Result(State.VALID, parseScopes(text), "");
    }
    String network =
        firstLine(
            text,
            "dial tcp",
            "no such host",
            "connection refused",
            "network is unreachable",
            "proxyconnect",
            "i/o timeout",
            "tls handshake timeout");
    if (network != null) return new Result(State.NETWORK_ERROR, List.of(), sanitize(network));
    if (exitCode != 0) {
      String line = lastMeaningfulLine(text);
      return new Result(State.ERROR, List.of(), sanitize(line));
    }
    // 退出 0 却没有 HTTP 状态行：输出形态未知，不能当成功。
    return new Result(State.ERROR, List.of(), sanitize(lastMeaningfulLine(text)));
  }

  /** 从响应头解析 token 实际作用域；只取 scope 名，保持 GitHub 返回顺序。 */
  static List<String> parseScopes(String output) {
    List<String> scopes = new ArrayList<>();
    for (String raw : output.split("\n")) {
      String line = raw.strip();
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      if (!line.substring(0, colon).strip().equalsIgnoreCase("x-oauth-scopes")) continue;
      for (String part : line.substring(colon + 1).split("[,\\s]+")) {
        String scope = part.strip().replace("\"", "").replace("'", "");
        if (!scope.isEmpty() && scope.matches("[A-Za-z0-9:_-]{1,64}") && !scopes.contains(scope))
          scopes.add(scope);
      }
    }
    return scopes;
  }

  private static String firstLine(String text, String... needles) {
    for (String line : text.split("\n")) {
      String stripped = line.strip();
      if (stripped.isEmpty()) continue;
      String lower = stripped.toLowerCase(Locale.ROOT);
      for (String needle : needles) {
        if (lower.contains(needle)) return stripped;
      }
    }
    return null;
  }

  private static String lastMeaningfulLine(String text) {
    String[] lines = text.split("\n");
    for (int index = lines.length - 1; index >= 0; index--) {
      String stripped = lines[index].strip();
      if (stripped.isEmpty()) continue;
      if (stripped.contains("requests use the SunsetRNE GitHub identity")) continue;
      if (stripped.startsWith("* ")) continue;
      return stripped;
    }
    return "";
  }

  /** 去令牌形状、URL 查询串与超长内容；展示错误不需要这些。 */
  private static String sanitize(String value) {
    if (value == null || value.isEmpty()) return "";
    String cleaned = SensitiveData.redact(value);
    cleaned = cleaned.replaceAll("(https?://[^\\s\"'?]+)\\?[^\\s\"']*", "$1");
    cleaned = cleaned.strip();
    return cleaned.length() <= MAX_DETAIL
        ? cleaned
        : cleaned.substring(0, MAX_DETAIL).strip() + "…";
  }
}
