package com.deepseekharness.app.util;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/** 网页只传入下载请求；作者和版本最终由实际压缩包校验，不执行链接中的命令。 */
public final class PluginInstallLink {
    public final String url, sha256, name, version, builtin;
    private PluginInstallLink(Map<String, String> params) {
        url=params.getOrDefault("url", ""); sha256=params.getOrDefault("sha256", "");
        name=params.getOrDefault("name", ""); version=params.getOrDefault("version", ""); builtin=params.getOrDefault("builtin", "");
    }
    public static PluginInstallLink parse(String value) {
        try {
            if (value == null || value.length() > 12000) throw new IllegalArgumentException();
            URI uri = new URI(value);
            boolean custom = "dsha".equalsIgnoreCase(uri.getScheme()) && "install".equalsIgnoreCase(uri.getHost())
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
            boolean web = "https".equalsIgnoreCase(uri.getScheme()) && "dsha.cc".equalsIgnoreCase(uri.getHost())
                    && ("/install".equals(uri.getPath()) || "/install/".equals(uri.getPath()));
            if ((!custom && !web) || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            Map<String, String> params = new HashMap<>();
            String query = uri.getRawQuery();
            if (query == null) throw new IllegalArgumentException();
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2) throw new IllegalArgumentException();
                String key = URLDecoder.decode(parts[0], "UTF-8"), item = URLDecoder.decode(parts[1], "UTF-8");
                if (!java.util.Arrays.asList("url", "sha256", "name", "version", "builtin").contains(key)
                        || params.put(key, item) != null || item.matches("(?s).*\\p{Cntrl}.*")) throw new IllegalArgumentException();
            }
            PluginInstallLink link = new PluginInstallLink(params);
            if (!link.builtin.isEmpty()) {
                if (!validName(link.builtin) || !link.url.isEmpty()) throw new IllegalArgumentException();
            } else {
                PluginSource.parse(link.url);
                if (!link.sha256.isEmpty() && !link.sha256.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException();
                if (!link.name.isEmpty() && !validName(link.name)) throw new IllegalArgumentException();
                if (link.version.length() > 100) throw new IllegalArgumentException();
            }
            return link;
        } catch (Exception e) { throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("安装链接无效，请从 dsha.cc 重新选择插件，或在插件页粘贴下载链接")); }
    }
    private static boolean validName(String value) { return value.length() <= 214 && value.matches("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*"); }
}
