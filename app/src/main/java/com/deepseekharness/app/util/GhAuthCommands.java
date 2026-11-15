package com.deepseekharness.app.util;

/**
 * 固定 GitHub CLI 命令。登录/重新授权按 T71 约定走 {@code gh auth login --web} 与 {@code gh auth
 * refresh}，不实现自建 device flow，也不在命令中嵌入任何 token 或用户输入。
 *
 * <p>返回的 guest 命令以 {@code /bin/bash -lc} 运行，并在命令结束后 {@code exec /bin/bash -l}，
 * 让终端会话继续可用；关闭、维护屏障与保护票仍走普通 PTY 路径。退出登录只删除本机记录，
 * 不撤销 GitHub 远端授权。
 */
public final class GhAuthCommands {
  /** gh 的网页登录会显示这个地址与一次性验证码；页面引导文案使用同一常量。 */
  public static final String BROWSER_URL = "https://github.com/login/device";

  private static final String HOST = GhManagedInstall.HOST;

  private GhAuthCommands() {}

  public static String login() {
    return "gh auth login --web --hostname " + HOST + " --git-protocol https";
  }

  public static String refresh() {
    return "gh auth refresh --hostname " + HOST;
  }

  /** gh auth status 需要网络核对 token；只作为终端里的只读详情入口。 */
  public static String status() {
    return "gh auth status --hostname " + HOST;
  }

  /**
   * 只读在线校验：带响应头请求 {@code /user}。
   *
   * <p>比 {@code gh auth status} 更能区分失败原因：TLS/CA 失败会打印 x509 错误，401 会打印
   * Bad credentials；成功时响应头 {@code X-OAuth-Scopes} 给出 token 实际作用域。命令只读，
   * 不修改 hosts.yml 或远端状态。
   */
  public static String probe() {
    return "gh api -i /user";
  }

  /**
   * 构造退出命令；账号来自 hosts.yml，只接受 GitHub 允许的字符集，再用 POSIX 引号包裹。
   * 账号为空时由 gh 在唯一账号场景下自行选择。
   */
  public static String logout(String account) {
    StringBuilder command = new StringBuilder("gh auth logout --hostname ").append(HOST);
    if (account != null && !account.isEmpty()) {
      if (!validAccount(account)) throw new IllegalArgumentException("GH_ACCOUNT_INVALID");
      command.append(" --user ").append(ShellQuote.arg(account));
    }
    return command.toString();
  }

  public static String[] loginGuestArgv() {
    return guestArgv(login());
  }

  public static String[] refreshGuestArgv() {
    return guestArgv(refresh());
  }

  public static String[] statusGuestArgv() {
    return guestArgv(status());
  }

  /** 固定 argv：{@code /bin/bash -lc <固定命令>}；登录完成后回到登录 shell。 */
  public static String[] guestArgv(String command) {
    if (command == null || command.isEmpty())
      throw new IllegalArgumentException("GH_COMMAND_EMPTY");
    return new String[] {"/bin/bash", "-lc", command + "\nexec /bin/bash -l"};
  }

  /** GitHub 账号：字母数字与连字符，首尾不能是连字符，最多 39 字符。 */
  public static boolean validAccount(String login) {
    return login != null
        && login.length() <= 39
        && login.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
  }
}
