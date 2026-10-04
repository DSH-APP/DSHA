package com.deepseekharness.app.util;

/** 仅插件任务采用此策略，不改终端或用户 npmrc。 */
public enum PluginDownloadSource {
  AUTO("auto"),
  OFFICIAL("official"),
  MIRROR("mirror");

  public final String value;

  PluginDownloadSource(String value) {
    this.value = value;
  }

  public static PluginDownloadSource parse(String value) {
    for (PluginDownloadSource source : values()) if (source.value.equals(value)) return source;
    return AUTO;
  }
}
