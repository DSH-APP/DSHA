package com.deepseekharness.app.plugins;

/** 插件身份的唯一归一化点：可见包名、运行证据名和历史别名由同一规则映射。 */
public final class PluginIdentity {
  /** 官方 Auto review 的历史包装名；包装本身没有运行逻辑，只暴露锁定官方实验包。 */
  public static final String AUTO_REVIEW_ALIAS = "dsh-auto-review";

  public static final String AUTO_REVIEW_CANONICAL = "@deepseek-ai/dsh-experimental-auto-review";

  private PluginIdentity() {}

  /** 仅合并已有官方历史别名；其它名字保持原样，不能把不同插件折叠成同一身份。 */
  public static String canonical(String name) {
    return AUTO_REVIEW_ALIAS.equals(name) ? AUTO_REVIEW_CANONICAL : name;
  }

  /**
   * 卡片是官方包名、运行证据却来自历史包装名：包装壳自身没有业务代码，只由 patch 插入官方包，
   * 它的版本（如 0.1.0）本来就不等于官方包版本（如 0.2.2-rc.1）。这条路径只核对加载状态，
   * 不能按版本不等判成旧模块冒充；其它名字组合不算兼容壳，版本不等仍须保持重启提示。
   */
  public static boolean legacyShim(String cardName, String observedName) {
    return AUTO_REVIEW_CANONICAL.equals(cardName) && AUTO_REVIEW_ALIAS.equals(observedName);
  }

  /** profile 长度前缀避免插件名里的分隔符把不同 profile 拼成同一键。 */
  public static String key(String profile, String name) {
    return profile.length() + ":" + profile + ":" + canonical(name);
  }
}
