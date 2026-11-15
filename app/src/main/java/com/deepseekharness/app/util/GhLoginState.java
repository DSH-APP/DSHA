package com.deepseekharness.app.util;

/** 把 hosts.yml 投影与受管 gh 状态合成界面可判定的单一登录状态；不含 UI 文案。 */
public final class GhLoginState {
  public enum Login {
    /** 没有可用的 GitHub.com 登录。 */
    NOT_LOGGED_IN,
    /** 账号与 token 都存在。 */
    LOGGED_IN,
    /** 账号存在但文件里没有 token（可能由环境变量或钥匙串提供）。 */
    CREDENTIAL_MISSING,
    /** 有 token 但读不到账号名（旧格式缺 user）。 */
    ACCOUNT_UNKNOWN,
    /** hosts.yml 结构不受支持；不猜测字段。 */
    MALFORMED,
    /** 文件里只有其他 GitHub 主机（如企业版）。 */
    OTHER_HOSTS_ONLY
  }

  private GhLoginState() {}

  /**
   * @param hostsPresent hosts.yml 是否存在
   * @param hosts 解析结果；不存在时传 null
   */
  public static Login classify(boolean hostsPresent, GhHostsFile.Result hosts) {
    if (hosts == null || !hostsPresent) return Login.NOT_LOGGED_IN;
    if (hosts.status == GhHostsFile.Status.MALFORMED) return Login.MALFORMED;
    boolean githubAccount = hosts.login != null || !hosts.accounts.isEmpty();
    if (!githubAccount && !hosts.credentialStored && !hosts.otherHosts.isEmpty())
      return Login.OTHER_HOSTS_ONLY;
    if (hosts.credentialStored && githubAccount)
      return hosts.login == null ? Login.ACCOUNT_UNKNOWN : Login.LOGGED_IN;
    if (hosts.credentialStored) return Login.ACCOUNT_UNKNOWN;
    if (githubAccount) return Login.CREDENTIAL_MISSING;
    return Login.NOT_LOGGED_IN;
  }

  /** 退出登录只在确有本机登录记录时可用；其他状态没有可删除的账号。 */
  public static boolean canLogout(Login login) {
    return login == Login.LOGGED_IN || login == Login.ACCOUNT_UNKNOWN;
  }
}
