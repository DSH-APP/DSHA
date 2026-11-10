package com.deepseekharness.app.saf;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 跨应用文件访问的纯逻辑部分（不 import 任何 Android API，可 JVM 单测）。
 *
 * <p>Android 的 {@link SafTree} 只负责跨进程调用，路径规范化、MIME 猜测与文本判定都在这里，
 * 与仓库「纯逻辑放纯类并配单测」的约定一致。
 */
public final class SafPaths {
  /** 路径段数上限，防止病态路径把解析拖成 O(n²)。 */
  public static final int MAX_SEGMENTS = 32;

  private SafPaths() {}

  /** 规范化：去掉首尾空白与开头斜杠；拒绝 {@code ..}、反斜杠与控制字符；空路径合法（表示根）。 */
  public static String normalize(String path) throws IllegalArgumentException {
    String value = path == null ? "" : path.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    if (value.isEmpty()) return "";
    for (String segment : value.split("/")) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
        throw new IllegalArgumentException("BAD_PATH: " + path);
      if (segment.indexOf('\\') >= 0) throw new IllegalArgumentException("BAD_PATH: " + path);
      for (int i = 0; i < segment.length(); i++)
        if (segment.charAt(i) < ' ') throw new IllegalArgumentException("BAD_PATH: " + path);
    }
    if (value.split("/").length > MAX_SEGMENTS)
      throw new IllegalArgumentException("PATH_TOO_DEEP: " + path);
    return value;
  }

  /** 按扩展名猜 MIME；猜不到给 octet-stream（新建文档时 Provider 可能据此拒绝，也算明确失败）。 */
  public static String mimeOf(String name) {
    int dot = name.lastIndexOf('.');
    String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    switch (extension) {
      case "txt":
      case "log":
      case "md":
        return "text/plain";
      case "json":
        return "application/json";
      case "xml":
        return "text/xml";
      case "html":
        return "text/html";
      case "js":
        return "application/javascript";
      case "csv":
        return "text/csv";
      case "png":
        return "image/png";
      case "jpg":
      case "jpeg":
        return "image/jpeg";
      case "webp":
        return "image/webp";
      case "gif":
        return "image/gif";
      case "pdf":
        return "application/pdf";
      case "db":
      case "sqlite":
      case "sqlite3":
        return "application/x-sqlite3";
      case "zip":
        return "application/zip";
      default:
        return "application/octet-stream";
    }
  }

  /** 文本判定：整个缓冲无 NUL、能按 UTF-8 严格解出且不产生替换符，才当文本返回。 */
  public static boolean looksText(byte[] data) {
    if (data == null) return false;
    // 空文件是合法文本（read 返回空内容），不应让调用方误以为它是二进制。
    if (data.length == 0) return true;
    for (byte b : data) if (b == 0) return false;
    try {
      // 严格解码：任何非法序列都会产生 U+FFFD，直接判为二进制（调用方用 base64=1 / pull）。
      String value = new String(data, StandardCharsets.UTF_8);
      return value.indexOf('\uFFFD') < 0;
    } catch (Throwable ignored) {
      return false;
    }
  }
}
