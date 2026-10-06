package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** LAN 的固定失败响应；不回显请求、地址、凭据或后端异常。 */
public final class LanFailureResponse {
  private LanFailureResponse() {}

  public enum Reason {
    TOKEN_MISSING("token-missing"),
    TOKEN_INVALID("token-invalid"),
    TOKEN_UNAVAILABLE("token-unavailable"),
    BACKEND_AUTH_NOT_READY("backend-auth-not-ready"),
    PROXY_BUSY("proxy-busy"),
    BACKEND_UNREACHABLE("backend-unreachable"),
    REQUEST_TIMEOUT("request-timeout"),
    BAD_REQUEST("bad-request");
    final String header;

    Reason(String header) {
      this.header = header;
    }
  }

  public static Reason forStatus(int status) {
    return switch (status) {
      case 401 -> Reason.TOKEN_INVALID;
      case 408 -> Reason.REQUEST_TIMEOUT;
      case 502 -> Reason.BACKEND_UNREACHABLE;
      case 503 -> Reason.PROXY_BUSY;
      default -> Reason.BAD_REQUEST;
    };
  }

  public static String message(Reason reason) {
    return switch (reason) {
      case TOKEN_MISSING ->
          UiText.choose(
              "局域网地址缺少访问凭据。请回到 DSHA 启动页，点局域网地址并复制完整连接链接。",
              "The LAN address has no access credential. Open the DSHA Launch page, tap the LAN address, and copy the complete connection link.");
      case TOKEN_INVALID ->
          UiText.choose(
              "局域网访问凭据已失效或不匹配。请回到 DSHA 启动页重新复制连接链接；DSH 重启或关闭局域网访问后，旧链接和浏览器凭据会失效。",
              "The LAN credential has expired or does not match. Copy a new connection link from the DSHA Launch page. Restarting DSH or turning LAN access off invalidates old links and browser credentials.");
      case TOKEN_UNAVAILABLE ->
          UiText.choose(
              "局域网访问凭据尚未就绪。请在 DSHA 中启动 DSH，等待局域网地址出现后重新复制连接链接。",
              "The LAN credential is not ready. Start DSH in DSHA, wait for the LAN address, then copy a new connection link.");
      case BACKEND_AUTH_NOT_READY ->
          UiText.choose(
              "局域网代理正在等待 DSH 认证。请回到 DSHA 确认 DSH 已启动，再刷新此页；此状态不表示局域网链接失效。",
              "The LAN proxy is waiting for DSH authentication. Confirm DSH is running in DSHA, then refresh this page. This does not mean the LAN link has expired.");
      case PROXY_BUSY ->
          UiText.choose(
              "局域网代理暂时繁忙。请稍后刷新；持续失败时请在 DSHA 中查看启动日志。",
              "The LAN proxy is temporarily busy. Refresh shortly. If this persists, inspect the startup log in DSHA.");
      case BACKEND_UNREACHABLE ->
          UiText.choose(
              "局域网代理无法连接 DSH。请回到 DSHA 确认 DSH 正在运行并查看启动日志，再刷新此页。",
              "The LAN proxy could not connect to DSH. Confirm DSH is running and inspect the startup log in DSHA, then refresh this page.");
      case REQUEST_TIMEOUT ->
          UiText.choose("读取请求超时。请检查网络后重试。", "The request timed out. Check the network and retry.");
      case BAD_REQUEST ->
          UiText.choose(
              "请求格式无效或超出限制。请使用 DSHA 复制的完整连接链接重试。",
              "The request is invalid or exceeds a limit. Retry with the complete connection link copied from DSHA.");
    };
  }

  public static void write(OutputStream out, int status, Reason reason, boolean headOnly)
      throws IOException {
    String phrase =
        switch (status) {
          case 400 -> "Bad Request";
          case 401 -> "Unauthorized";
          case 408 -> "Request Timeout";
          case 413 -> "Content Too Large";
          case 414 -> "URI Too Long";
          case 431 -> "Request Header Fields Too Large";
          case 502 -> "Bad Gateway";
          case 503 -> "Service Unavailable";
          default -> "Request Failed";
        };
    byte[] body = (message(reason) + "\n").getBytes(StandardCharsets.UTF_8);
    String headers =
        "HTTP/1.1 "
            + status
            + " "
            + phrase
            + "\r\n"
            + "Content-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n"
            + "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n"
            + "X-Dsha-Lan-Reason: "
            + reason.header
            + "\r\n"
            + (status == 401
                ? "WWW-Authenticate: Bearer realm=\"DSHA LAN\"\r\nSet-Cookie: dsha_lan=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict\r\n"
                : "")
            + "Content-Length: "
            + body.length
            + "\r\nConnection: close\r\n\r\n";
    out.write(headers.getBytes(StandardCharsets.US_ASCII));
    if (!headOnly) out.write(body);
    out.flush();
  }
}
