package com.deepseekharness.app.util;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从完整链接、仓库简写或一段分享文字中识别唯一的安装来源。 */
public final class PluginSource {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"，。；！）】]+",
            Pattern.CASE_INSENSITIVE);
    public final GitHubRef github;
    public final String url;
    public final String npm;

    private PluginSource(GitHubRef github, String url) {
        this.github = github;
        this.url = url;
        this.npm = "";
    }

    private PluginSource(String npm) {
        this.github = GitHubRef.invalid(); this.url = ""; this.npm = npm;
    }

    public static PluginSource parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("请粘贴插件链接或 owner/repo"));
        String value = raw.trim();
        String spec = value.startsWith("npm:") ? value.substring(4) : value;
        if (spec.length() <= 300 && spec.matches("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*(?:@[A-Za-z0-9.^~*+_-]+)?"))
            return new PluginSource(spec);
        Matcher matcher = URL.matcher(value);
        if (matcher.find()) {
            value = matcher.group().replaceFirst("[)\\]}>.,;!]+$", "");
            if (matcher.find()) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("识别到多个链接，请每次只粘贴一个插件链接"));
        }
        GitHubRef github = GitHubRef.parse(value);
        if (github.valid) return new PluginSource(github, github.archiveUrl);
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
            // GitHub 页面解析失败不能当压缩包下载，否则 issues/tree 错误会安装默认分支。
            if ("https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && uri.getUserInfo() == null && uri.getPort() == -1
                    && !host.equalsIgnoreCase("github.com") && !host.equalsIgnoreCase("www.github.com")
                    && !host.equalsIgnoreCase("codeload.github.com")
                    && (path.endsWith(".zip") || path.endsWith(".tar") || path.endsWith(".tar.gz")
                    || path.endsWith(".tgz")))
                return new PluginSource(GitHubRef.invalid(), value);
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("无法识别：支持 npm 包名、GitHub 仓库、分支/子目录、Release 下载链接或 HTTPS 压缩包直链"));
    }

    public String command() {
        if (!npm.isEmpty()) return "npm " + ShellQuote.arg(npm);
        if (!url.isEmpty()) return "download " + ShellQuote.arg(url);
        if ("release".equals(github.kind)) return "release " + ShellQuote.arg(github.owner)
                + " " + ShellQuote.arg(github.repo) + " " + ShellQuote.arg(github.revision);
        return "github " + ShellQuote.arg(github.owner) + " " + ShellQuote.arg(github.repo)
                + " " + ShellQuote.arg(github.treePath);
    }

    public String description() {
        if (!npm.isEmpty()) return com.deepseekharness.app.util.UiText.text("npm 插件包 · ") + npm;
        if (!url.isEmpty()) {
            try { return com.deepseekharness.app.util.UiText.text("插件压缩包 · ") + new URI(url).getHost(); }
            catch (Exception ignored) { return com.deepseekharness.app.util.UiText.text("插件压缩包"); }
        }
        return github.owner + "/" + github.repo
                + ("release".equals(github.kind) ? " · Release " + github.revision
                : github.treePath.isEmpty() ? com.deepseekharness.app.util.UiText.text(" · 默认分支") : com.deepseekharness.app.util.UiText.text("\n分支/目录：") + github.treePath);
    }
}
