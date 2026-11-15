package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读取 gh {@code hosts.yml} 的非密钥元数据（账号、Git 协议、是否保存 token、其他主机）。
 *
 * <p>gh CLI 在 Linux 上把 OAuth token 明文写在 hosts.yml；网页、插件与终端当前都能读这个文件。
 * 本解析器只投影界面需要的信息，<b>不保留</b> {@code oauth_token} 的值：出现该键只记一个布尔位，
 * 任何字段、{@code toString()} 与异常信息都不含 token。需要真正与容器隔离凭据属于 T50 设计范围。
 *
 * <p>格式严格但有界：只接受 gh 写出的标量 YAML 子集（host → 键、{@code users:} → 账号 → 键），
 * 拒绝 tab、重复 github.com、缺失冒号、未知层级与超长行；未知<b>键</b>按前向兼容忽略，但结构错误
 * 直接报 {@link Status#MALFORMED}，不猜测字段。其他主机只记录主机名，不解析其内容。
 */
public final class GhHostsFile {
  public enum Status {
    OK,
    MALFORMED
  }

  /** 解析结果；字段是不可变投影，不含任何 token 值。 */
  public static final class Result {
    public final Status status;

    /** 当前生效账号；无 user 键且不是唯一账号时为 null。 */
    public final String login;

    /** github.com 是否写有 token（值本身从未被保留）。 */
    public final boolean credentialStored;

    /** 是否使用 gh 2.x 的 users: 映射布局。 */
    public final boolean usersLayout;

    /** 是否还有旧版顶层 oauth_token 布局。 */
    public final boolean legacyLayout;

    /** github.com 下记录的全部账号（保持文件顺序）。 */
    public final List<String> accounts;

    /** hosts.yml 若额外记录了作用域则保留；gh 官方 CLI 不写该字段。 */
    public final List<String> scopes;

    /** https / ssh；未记录时为空串。 */
    public final String gitProtocol;

    /** 同文件中的其他主机名（不含 github.com）。 */
    public final List<String> otherHosts;

    /** github.com 下 oauth_token 键的数量；只用于诊断，不含任何值。 */
    public final int tokenFields;

    private Result(
        Status status,
        String login,
        boolean credentialStored,
        boolean usersLayout,
        boolean legacyLayout,
        List<String> accounts,
        List<String> scopes,
        String gitProtocol,
        List<String> otherHosts,
        int tokenFields) {
      this.status = status;
      this.login = login;
      this.credentialStored = credentialStored;
      this.usersLayout = usersLayout;
      this.legacyLayout = legacyLayout;
      this.accounts = List.copyOf(accounts);
      this.scopes = List.copyOf(scopes);
      this.gitProtocol = gitProtocol;
      this.otherHosts = List.copyOf(otherHosts);
      this.tokenFields = tokenFields;
    }

    public static Result malformed() {
      return new Result(
          Status.MALFORMED, null, false, false, false, List.of(), List.of(), "", List.of(), 0);
    }

    /** 不含 token 的稳定投影；账号与主机属于用户数据，日志仍不应记录整段。 */
    @Override
    public String toString() {
      return "GhHostsFile{status="
          + status
          + ", login="
          + login
          + ", credentialStored="
          + credentialStored
          + ", usersLayout="
          + usersLayout
          + ", accounts="
          + accounts
          + ", otherHosts="
          + otherHosts
          + "}";
    }
  }

  /** 与单次 YAML 解析的行数/行长上限一致；超过视为不可信输入。 */
  public static final int MAX_LINES = 512;

  public static final int MAX_LINE = 4096;

  private static final int MAX_ACCOUNTS = 32;
  private static final int MAX_SCOPES = 64;
  private static final int MAX_HOSTS = 16;

  private GhHostsFile() {}

  /**
   * 解析 hosts.yml 文本；空文本表示尚未登录（{@link Status#OK}）。输入必须已有界
   * （{@link GhManagedInstall#MAX_HOSTS_BYTES}），本方法不读取文件。
   */
  public static Result parse(String text) {
    String value = text == null ? "" : text;
    if (value.length() > GhManagedInstall.MAX_HOSTS_BYTES) return Result.malformed();
    String[] lines = value.split("\n", -1);
    if (lines.length > MAX_LINES) return Result.malformed();

    String currentHost = null;
    boolean github = false;
    boolean githubSeen = false;
    int hostIndent = -1;
    String section = ""; // ""、users、user
    String currentAccount = null;
    String topUser = "";
    String gitProtocol = "";
    boolean topToken = false;
    boolean usersLayout = false;
    int tokenFields = 0;
    final Map<String, Boolean> accountTokens = new LinkedHashMap<>();
    final List<String> accounts = new ArrayList<>();
    final List<String> scopes = new ArrayList<>();
    final List<String> otherHosts = new ArrayList<>();

    for (String line : lines) {
      String raw = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
      if (raw.length() > MAX_LINE) return Result.malformed();
      if (raw.indexOf('\t') >= 0) return Result.malformed();
      String content = raw.strip();
      if (content.isEmpty() || content.startsWith("#")) continue;

      int indent = 0;
      while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
      if (indent == raw.length()) continue;

      int colon = content.indexOf(':');
      if (colon <= 0) return Result.malformed();
      String key = content.substring(0, colon).strip();
      String rest = content.substring(colon + 1).strip();
      if (key.isEmpty() || key.indexOf(' ') >= 0) return Result.malformed();

      if (indent == 0) {
        if (!rest.isEmpty()) return Result.malformed();
        if (!hostName(key)) return Result.malformed();
        github = GhManagedInstall.HOST.equals(key);
        if (github) {
          if (githubSeen) return Result.malformed();
          githubSeen = true;
        } else if (otherHosts.size() < MAX_HOSTS && !otherHosts.contains(key)) {
          otherHosts.add(key);
        }
        currentHost = key;
        hostIndent = -1;
        section = "";
        currentAccount = null;
        continue;
      }

      if (currentHost == null) return Result.malformed();
      if (!github) continue; // 其他主机的内容不是本页契约，整块忽略。
      if (hostIndent < 0) hostIndent = indent;

      if (indent == hostIndent) {
        section = "";
        currentAccount = null;
        if (key.equals("user")) {
          String scalar = scalar(rest);
          if (scalar == null) return Result.malformed();
          if (!scalar.isEmpty() && !GhAuthCommands.validAccount(scalar)) return Result.malformed();
          topUser = scalar;
        } else if (key.equals("oauth_token")) {
          String scalar = scalar(rest);
          if (scalar == null || scalar.isEmpty()) return Result.malformed();
          topToken = true;
          tokenFields++;
        } else if (key.equals("git_protocol")) {
          String scalar = scalar(rest);
          if (scalar == null) return Result.malformed();
          if (!scalar.isEmpty() && !scalar.matches("[A-Za-z0-9+._-]{1,64}"))
            return Result.malformed();
          gitProtocol = scalar;
        } else if (key.equals("users")) {
          if (!rest.isEmpty()) return Result.malformed();
          usersLayout = true;
          section = "users";
        } else if (key.equals("scopes")) {
          addScopes(scopes, rest);
        }
        continue;
      }

      if (hostIndent <= 0) return Result.malformed();
      if (indent == hostIndent * 2) {
        if (!"users".equals(section) && !"user".equals(section)) return Result.malformed();
        if (!rest.isEmpty()) return Result.malformed();
        if (!GhAuthCommands.validAccount(key)) return Result.malformed();
        if (accounts.size() >= MAX_ACCOUNTS || accountTokens.containsKey(key))
          return Result.malformed();
        accounts.add(key);
        accountTokens.put(key, false);
        currentAccount = key;
        section = "user";
        continue;
      }

      if (indent == hostIndent * 3) {
        if (!"user".equals(section) || currentAccount == null) return Result.malformed();
        if (key.equals("oauth_token")) {
          String scalar = scalar(rest);
          if (scalar == null || scalar.isEmpty()) return Result.malformed();
          accountTokens.put(currentAccount, true);
          tokenFields++;
        }
        continue;
      }

      return Result.malformed();
    }

    String login = topUser;
    if (login.isEmpty() && accounts.size() == 1) login = accounts.get(0);
    boolean credential = topToken;
    if (!credential && login != null && !login.isEmpty())
      credential = Boolean.TRUE.equals(accountTokens.get(login));
    if (!credential) {
      for (boolean stored : accountTokens.values()) {
        if (stored) {
          credential = true;
          break;
        }
      }
    }
    return new Result(
        Status.OK,
        login.isEmpty() ? null : login,
        credential,
        usersLayout,
        topToken && !usersLayout,
        accounts,
        scopes,
        gitProtocol,
        otherHosts,
        tokenFields);
  }

  /** 允许省略空值（{@code host:}）；去掉成对引号，拒绝不完整引号与锚点/流式集合。 */
  private static String scalar(String rest) {
    if (rest.isEmpty()) return "";
    char first = rest.charAt(0);
    if (first == '&'
        || first == '*'
        || first == '!'
        || first == '{'
        || first == '['
        || first == '|'
        || first == '>') return null;
    if (first == '"' || first == '\'') {
      if (rest.length() < 2 || rest.charAt(rest.length() - 1) != first) return null;
      return rest.substring(1, rest.length() - 1).strip();
    }
    return rest;
  }

  private static void addScopes(List<String> scopes, String value) {
    if (value.isEmpty()) return;
    String cleaned = value.strip();
    if (cleaned.startsWith("[") && cleaned.endsWith("]"))
      cleaned = cleaned.substring(1, cleaned.length() - 1);
    for (String part : cleaned.split("[,\\s]+")) {
      String scope = part.strip().replace("\"", "").replace("'", "");
      if (scope.isEmpty() || !scope.matches("[A-Za-z0-9:_-]{1,64}")) continue;
      if (!scopes.contains(scope) && scopes.size() < MAX_SCOPES) scopes.add(scope);
    }
  }

  private static boolean hostName(String value) {
    return value.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,252})");
  }
}
