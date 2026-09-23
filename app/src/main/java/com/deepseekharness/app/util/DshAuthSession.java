package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.function.BooleanSupplier;

/** 官方 BrowserAuth 交换与就绪检查；有总时限，不沿重定向转发进程凭据。 */
public final class DshAuthSession {
    private DshAuthSession() { }
    public enum Status { READY, NOT_READY, EXPIRED, INVALID_RESPONSE, CANCELLED }
    public static final class Result {
        public final Status status;
        public final String cookie, message;
        private Result(Status status, String cookie, String message) {
            this.status = status; this.cookie = cookie; this.message = message;
        }
        public boolean ready() { return status == Status.READY; }
    }
    private static Result failed(Status status, String message) { return new Result(status, null, message); }

    public static Result exchange(String authUrl, int port, BooleanSupplier current) {
        return exchange(authUrl, port, current, 12_000);
    }

    static Result exchange(String authUrl, int port, BooleanSupplier current, int budgetMs) {
        DshAuthUrl.Parsed parsed = DshAuthUrl.parse(authUrl);
        if (parsed == null || !parsed.loopbackBaseUrl.equals("http://127.0.0.1:" + port + "/"))
            return failed(Status.INVALID_RESPONSE, com.deepseekharness.app.util.UiText.text("本轮鉴权地址尚未就绪，请返回启动页查看状态"));
        long deadline = System.nanoTime() + Math.max(1, budgetMs) * 1_000_000L;
        Result last = failed(Status.NOT_READY, com.deepseekharness.app.util.UiText.text("Web 服务仍未就绪，请稍后重试或查看启动日志"));
        do {
            if (!current.getAsBoolean() || Thread.currentThread().isInterrupted()) return cancelled();
            try { last = attempt(parsed, deadline); }
            catch (java.net.SocketTimeoutException error) {
                last = failed(Status.NOT_READY, com.deepseekharness.app.util.UiText.text("Web 服务响应超时，请稍后重试或查看启动日志"));
            } catch (IOException error) {
                last = failed(Status.NOT_READY, com.deepseekharness.app.util.UiText.text("无法连接本机 Web 服务，请确认服务仍在运行后重试"));
            }
            if (!current.getAsBoolean() || Thread.currentThread().isInterrupted()) return cancelled();
            if (last.status != Status.NOT_READY) return last;
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) break;
            try { Thread.sleep(Math.min(450, left)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return cancelled(); }
        } while (System.nanoTime() < deadline);
        return last;
    }

    private static Result cancelled() { return failed(Status.CANCELLED, com.deepseekharness.app.util.UiText.text("Web 状态已变化，请等待本轮启动完成后重试")); }
    private static HttpURLConnection connect(String url, long deadline) throws IOException {
        int left = (int) Math.min(2500, (deadline - System.nanoTime()) / 1_000_000L);
        if (left <= 0) throw new java.net.SocketTimeoutException();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(left);
        connection.setReadTimeout(left);
        connection.setRequestProperty("Cache-Control", "no-store");
        return connection;
    }
    private static Result attempt(DshAuthUrl.Parsed parsed, long deadline) throws IOException {
        HttpURLConnection connection = connect(parsed.authUrl, deadline);
        String cookie;
        try {
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) return failed(Status.EXPIRED, com.deepseekharness.app.util.UiText.text("Web 鉴权链接已失效（HTTP ") + code + com.deepseekharness.app.util.UiText.text("），请重新启动服务后进入"));
            if (code >= 500) return failed(Status.NOT_READY, com.deepseekharness.app.util.UiText.text("Web 服务尚未就绪（HTTP ") + code + com.deepseekharness.app.util.UiText.text("），请稍后重试"));
            // dsh 0.1.7 switched the clean redirect to the directory-relative
            // `./`; retain `/` for older installed runtimes during coverage.
            String location = connection.getHeaderField("Location");
            if (code != 303 || !("/".equals(location) || "./".equals(location)))
                return failed(Status.INVALID_RESPONSE, com.deepseekharness.app.util.UiText.text("Web 鉴权响应异常（HTTP ") + code + com.deepseekharness.app.util.UiText.text("），请查看启动日志"));
            cookie = DshAuthUrl.extractCookie(connection.getHeaderFields());
            if (cookie == null || !cookie.matches("dsh-auth-[A-Za-z0-9_-]{43}=v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}"))
                return failed(Status.INVALID_RESPONSE, com.deepseekharness.app.util.UiText.text("Web 未返回有效的登录凭据，请重试或查看启动日志"));
        } finally { connection.disconnect(); }
        // 第二次请求同时验证 cookie 和服务就绪，避免只拿到 Cookie 就把 401 页面当作成功。
        connection = connect(parsed.loopbackBaseUrl, deadline);
        try {
            connection.setRequestProperty("Cookie", cookie);
            int code = connection.getResponseCode();
            if (code == 200) return new Result(Status.READY, cookie, com.deepseekharness.app.util.UiText.text("鉴权成功"));
            if (code == 401 || code == 403) return failed(Status.EXPIRED, com.deepseekharness.app.util.UiText.text("Web 未接受登录凭据（HTTP ") + code + com.deepseekharness.app.util.UiText.text("），请重试或重新启动服务"));
            return failed(code >= 500 ? Status.NOT_READY : Status.INVALID_RESPONSE,
                    com.deepseekharness.app.util.UiText.text("Web 页面尚不可用（HTTP ") + code + com.deepseekharness.app.util.UiText.text("），请查看启动日志"));
        } finally { connection.disconnect(); }
    }
}
