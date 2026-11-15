package com.deepseekharness.app.util;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 网页子资源失败的可导出诊断：只保留协议类别、错误码与去 query/fragment 的 path。
 *
 * <p>调用方即使误传完整地址，也只留下路径段；路径中的其它字符统一替换为下划线，长度有上限。日志按
 * 页面代次限流：同一路径去重、单位时间条数上限与每页总上限，避免一个坏资源刷满有界事件文件。
 */
public final class WebSubresourceDiagnostic {
  private WebSubresourceDiagnostic() {}

  public static final int PATH_LIMIT = 180;
  public static final int EVENTS_PER_WINDOW = 6;
  public static final long WINDOW_MS = 10_000L;
  public static final long DEDUPE_MS = 60_000L;
  public static final int EVENTS_PER_PAGE = 40;

  /** 只接受协议层可枚举的类别，避免把自由文本写进诊断。 */
  public static String phase(String value) {
    if ("RESOURCE".equals(value) || "HTTP".equals(value)) return value;
    return "RESOURCE";
  }

  /** 去 query/fragment 与主机，只保留路径；不解码，避免把编码后的敏感值还原出来。 */
  public static String path(String url) {
    String value = url == null ? "" : url.trim();
    int hash = value.indexOf('#');
    if (hash >= 0) value = value.substring(0, hash);
    int query = value.indexOf('?');
    if (query >= 0) value = value.substring(0, query);
    int scheme = value.indexOf("://");
    if (scheme >= 0) {
      int slash = value.indexOf('/', scheme + 3);
      value = slash < 0 ? "/" : value.substring(slash);
    } else if (value.startsWith("//")) {
      int slash = value.indexOf('/', 2);
      value = slash < 0 ? "/" : value.substring(slash);
    } else if (!value.startsWith("/")) {
      int slash = value.indexOf('/');
      value = slash < 0 ? "/" : value.substring(slash);
    }
    if (value.isEmpty()) value = "/";
    StringBuilder safe = new StringBuilder(Math.min(value.length(), PATH_LIMIT));
    for (int index = 0; index < value.length() && safe.length() < PATH_LIMIT; index++) {
      char c = value.charAt(index);
      boolean keep =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '/'
              || c == '%'
              || c == '.'
              || c == '_'
              || c == '-'
              || c == '~';
      safe.append(keep ? c : '_');
    }
    return safe.length() == 0 ? "/" : safe.toString();
  }

  /** 固定字段顺序与取值域，供 {@code DiagnosticLog} 原样记录。 */
  public static String describe(String phase, int code, String url) {
    return "phase=" + phase(phase) + " code=" + code + " path=" + path(url);
  }

  /**
   * 按页面代次限流。时间由调用方传入（{@code SystemClock.elapsedRealtime()}），便于单测复现；
   * 同一路径在去重窗口内只记一次，窗口内最多 {@code EVENTS_PER_WINDOW} 条，每页最多
   * {@code EVENTS_PER_PAGE} 条。
   */
  public static final class Limiter {
    private final Map<String, Long> seen = new HashMap<>();
    private final ArrayDeque<Long> accepted = new ArrayDeque<>();
    private int pageTotal;

    public static Limiter defaults() {
      return new Limiter();
    }

    public synchronized void reset() {
      seen.clear();
      accepted.clear();
      pageTotal = 0;
    }

    public synchronized boolean accept(String url, long now) {
      if (pageTotal >= EVENTS_PER_PAGE) return false;
      String key = path(url);
      Long previous = seen.get(key);
      if (previous != null && now - previous < DEDUPE_MS) return false;
      while (!accepted.isEmpty() && now - accepted.peekFirst() >= WINDOW_MS) accepted.removeFirst();
      if (accepted.size() >= EVENTS_PER_WINDOW) return false;
      seen.put(key, now);
      accepted.addLast(now);
      pageTotal++;
      // 去重表按页面总上限收敛；超出上限后新路径仍可记，但表不无限增长。
      if (seen.size() > EVENTS_PER_PAGE * 2) seen.clear();
      return true;
    }

    public synchronized int pageTotal() {
      return pageTotal;
    }
  }
}
