package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 内置插件（rootfs 烘焙的 dsh-device-shell-guide 等四个）的纯逻辑：清单解析、
 * 实体目录命名、profile 注册状态判定。无 Android 依赖，可单测。
 *
 * <p>注册契约（与 assets/register-builtin-plugins.py、selftest.py 保持一致）：
 * <ul>
 *   <li>web profile 的 {@code dsh.profile.bundles} 含插件名；</li>
 *   <li>{@code dependencies} 有 {@code link:/root/dsha-<name>} 声明；</li>
 *   <li>{@code profiles/web/node_modules/<name>} 是指向实体目录的链接。</li>
 * </ul>
 * 实体目录命名：插件名 {@code dsh-device-shell-guide} 对应 {@code /root/dsha-device-shell-guide}
 * （把 {@code dsh-} 前缀换成 {@code dsha-}）。
 */
public final class BuiltinPlugins {

    private BuiltinPlugins() {
    }

    /** 宿主核心继续参与启动和自检，用户列表只展示四个功能插件及第三方插件。 */
    public static boolean internal(String name) {
        return "@deepseek-ai/dsh-base".equals(name) || "@deepseek-ai/dsh-web-app".equals(name)
                || "dsh-app-integration".equals(name);
    }

    /** 内置插件清单兜底（dsha-builtin.txt 缺失/精简包时的固定名单，与脚本 DEFAULT_BUILTINS 一致）。 */
    public static final List<String> DEFAULT_BUILTINS = Collections.unmodifiableList(
            Arrays.asList(
                    "dsh-device-shell-guide",
                    "dsh-task-notifier",
                    "dsh-status-overlay",
                    "dsh-web-mobile"));

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
        if (pluginName.startsWith("dsh-")) {
            return "/root/dsha-" + pluginName.substring(4);
        }
        return "/root/dsha-" + pluginName;
    }

    /** 插件名对应的 web profile 内 node_modules 相对路径（用于查询链接是否就位）。 */
    public static String profileNodeModulesRel(String pluginName) {
        return "root/.dsh/profiles/web/node_modules/" + pluginName;
    }

    /** 该插件是否已列入 profile 的 dsh.profile.bundles。
     *  只在 bundles 数组区域里找，避免被 dependencies 里的同名 link: 误判为已注册。 */
    public static boolean inBundlesSection(String manifestJson, String pluginName) {
        if (manifestJson == null || pluginName == null) return false;
        int i = manifestJson.indexOf("\"bundles\"");
        if (i < 0) return false;
        int j = manifestJson.indexOf("]", i);
        return j > i && manifestJson.substring(i, j).contains("\"" + pluginName + "\"");
    }
}
