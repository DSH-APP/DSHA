package com.deepseekharness.app.util;

import java.util.Objects;

/**
 * 运行配置页的草稿快照：只描述需要点「保存配置」才写入的字段。
 *
 * <p>画中画开关与局域网密码在页面上即时生效，不进入草稿，因此也不计入未保存数。自动端口开启时
 * 输入框被禁用、保存沿用已存端口，所以端口文本只在固定端口模式下参与比较。
 */
public final class ConfigDraft {
  public final boolean proroot;
  public final boolean automaticPort;
  public final String port;
  public final String dnsMode;
  public final boolean lan;
  public final boolean staticLoader;
  public final boolean seccompDisabled;
  public final boolean desktop;
  public final boolean checkUpdate;
  public final boolean overlay;

  public ConfigDraft(
      boolean proroot,
      boolean automaticPort,
      String port,
      String dnsMode,
      boolean lan,
      boolean staticLoader,
      boolean seccompDisabled,
      boolean desktop,
      boolean checkUpdate,
      boolean overlay) {
    this.proroot = proroot;
    this.automaticPort = automaticPort;
    this.port = port == null ? "" : port.trim();
    this.dnsMode = dnsMode == null ? "auto" : dnsMode;
    this.lan = lan;
    this.staticLoader = staticLoader;
    this.seccompDisabled = seccompDisabled;
    this.desktop = desktop;
    this.checkUpdate = checkUpdate;
    this.overlay = overlay;
  }

  /** 与已保存快照相比的未保存字段数；{@code saved} 为空时视为没有改动。 */
  public int changesFrom(ConfigDraft saved) {
    if (saved == null) return 0;
    int n = 0;
    if (proroot != saved.proroot) n++;
    if (automaticPort != saved.automaticPort) n++;
    if (!automaticPort && !port.equals(saved.port)) n++;
    if (!dnsMode.equals(saved.dnsMode)) n++;
    if (lan != saved.lan) n++;
    if (staticLoader != saved.staticLoader) n++;
    if (seccompDisabled != saved.seccompDisabled) n++;
    if (desktop != saved.desktop) n++;
    if (checkUpdate != saved.checkUpdate) n++;
    if (overlay != saved.overlay) n++;
    return n;
  }

  /** 兼容选项是否偏离默认值（静态加载器开、seccomp 加速不禁用），用于折叠行的「已改」标记。 */
  public boolean compatibilityChanged() {
    return !staticLoader || seccompDisabled;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof ConfigDraft)) return false;
    ConfigDraft d = (ConfigDraft) other;
    return proroot == d.proroot
        && automaticPort == d.automaticPort
        && port.equals(d.port)
        && dnsMode.equals(d.dnsMode)
        && lan == d.lan
        && staticLoader == d.staticLoader
        && seccompDisabled == d.seccompDisabled
        && desktop == d.desktop
        && checkUpdate == d.checkUpdate
        && overlay == d.overlay;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        proroot,
        automaticPort,
        port,
        dnsMode,
        lan,
        staticLoader,
        seccompDisabled,
        desktop,
        checkUpdate,
        overlay);
  }
}
