package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 覆盖更新后可安全重建的精确缓存清单；不包含会话、配置、凭据、插件原件或工作区。 */
public final class RegenerableCachePaths {
    private RegenerableCachePaths() { }

    public static List<String> rootfs() {
        ArrayList<String> paths = new ArrayList<>();
        Collections.addAll(paths,
                "root/.dsh/.runtime-links-cache.json",
                "root/.dsh/.cache",
                "root/.dsh/session_projcache",
                "root/.dsh/plugin-updates.json",
                "root/.cache/dsh",
                "root/.cache/cordis");
        ArrayList<String> entities = new ArrayList<>(BuiltinPlugins.DEFAULT_BUILTINS);
        entities.add("dsh-app-integration");
        for (String name : entities) {
            String directory = BuiltinPlugins.entityDir(name).substring(1);
            paths.add(directory + "/.cache");
            paths.add(directory + "/dist-cache");
        }
        return Collections.unmodifiableList(paths);
    }

    public static List<String> webViewData() {
        return List.of(
                "app_webview/Default/Cache",
                "app_webview/Default/Code Cache",
                "app_webview/Default/GPUCache",
                "app_webview/Default/GrShaderCache",
                "app_webview/Default/DawnCache");
    }

    public static boolean safeRelative(String path) {
        return path != null && !path.isEmpty() && !path.startsWith("/") && !path.contains("\\")
                && !path.contains("..") && !path.equals("root/.dsh") && !path.startsWith("root/.dsh/profiles/")
                && !path.startsWith("root/.dsh/plugin-src/") && !path.startsWith("root/.dsh/plugin-previews/")
                && !path.startsWith("root/.dsh/sessions/");
    }
}
