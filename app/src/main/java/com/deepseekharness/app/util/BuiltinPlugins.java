package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 签名内置插件的纯逻辑：清单解析、
 * 实体目录命名及系统插件归属。无 Android 依赖，可单测。
 *
 * <p>注册契约来自 assets/builtin-plugins.json 与生成的 BuiltinPluginRegistry：
 * <ul>
 *   <li>web profile 的 {@code dsh.profile.bundles} 含插件名；</li>
 *   <li>{@code dependencies} 有 {@code link:/root/dsha-<name>} 声明；</li>
 *   <li>{@code profiles/web/node_modules/<name>} 是指向实体目录的链接。</li>
 * </ul>
 * 实体目录命名：插件名 {@code dsh-device-shell-guide} 对应 {@code /root/dsha-device-shell-guide}
 * （把 {@code dsh-} 前缀换成 {@code dsha-}）。
 */
public final class BuiltinPlugins {

  private BuiltinPlugins() {}

  /** 宿主核心参与启动和自检；用户列表遵循生成的可见插件名单。 */
  public static boolean internal(String name) {
    return BuiltinPluginRegistry.INTERNAL.contains(name);
  }

  /** 内置插件清单兜底（dsha-builtin.txt 缺失/精简包时的固定名单，与脚本 DEFAULT_BUILTINS 一致）。 */
  public static final List<String> DEFAULT_BUILTINS = BuiltinPluginRegistry.DEFAULT;

  /** 当前签名 APK 独占的系统插件；旧 profile/归档不得提供同名源码。 */
  public static final List<String> SIGNED_BUILTINS;

  public static final List<String> SYSTEM_PLUGINS;

  static {
    List<String> names = new ArrayList<>(BuiltinPluginRegistry.SIGNED);
    SIGNED_BUILTINS = Collections.unmodifiableList(names);
    List<String> system = new ArrayList<>(names);
    system.addAll(BuiltinPluginRegistry.OFFICIAL);
    SYSTEM_PLUGINS = Collections.unmodifiableList(system);
  }

  public static boolean system(String name) {
    return SYSTEM_PLUGINS.contains(name);
  }

  /** 解析 dsha-builtin.txt 内容：每行一个插件名，跳过空行与 # 注释。 */
  public static List<String> parseBuiltinNames(String content) {
    List<String> out = new ArrayList<>();
    if (content == null) return out;
    for (String ln : content.split("\n")) {
      String t = ln.trim();
      if (t.isEmpty() || t.startsWith("#")) continue;
      if (!out.contains(t)) out.add(t);
    }
    return out;
  }

  /** 插件名 → 其实体目录（/root/dsha-<name>，兼容 dsh- 前缀命名）。 */
  public static String entityDir(String pluginName) {
    if (pluginName == null) return "";
    String known = BuiltinPluginRegistry.guestDirectory(pluginName);
    if (!known.isEmpty()) return known;
    if (pluginName.startsWith("dsh-")) {
      return "/root/dsha-" + pluginName.substring(4);
    }
    return "/root/dsha-" + pluginName;
  }

  /** 插件名对应的 web profile 内 node_modules 相对路径（用于查询链接是否就位）。 */
  public static String profileNodeModulesRel(String pluginName) {
    return "root/.dsh/profiles/web/node_modules/" + pluginName;
  }
}
