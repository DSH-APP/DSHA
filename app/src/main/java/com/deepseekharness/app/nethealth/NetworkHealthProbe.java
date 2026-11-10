package com.deepseekharness.app.nethealth;

import android.os.SystemClock;
import com.deepseekharness.app.util.NetworkHealthPolicy;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 宿主侧出网探测：一次 DNS 解析 + 一次无凭据 HTTPS HEAD。
 *
 * <p>App 进程与容器同 UID，受同一条系统网络规则，宿主探测能代表「这个 UID 能否出网」。只看能否建立 TLS
 * 并收到任意 HTTP 状态码（401/403 也算通），不跟随重定向、不读正文、不带 Cookie 或 key。必须在工作线程调用。
 */
final class NetworkHealthProbe {
  /** 默认目标：DSH 默认模型服务。只取主机，不带路径。 */
  static final String HOST = "api.deepseek.com";

  private static final int TIMEOUT_MS = 5_000;

  private NetworkHealthProbe() {}

  static NetworkHealthPolicy.Probe run() {
    long start = SystemClock.elapsedRealtime();
    boolean dnsOk;
    String error = "";
    // InetAddress 没有超时参数；放到单独线程限时，超时只放弃结果不重试。
    ExecutorService lookup = Executors.newSingleThreadExecutor();
    try {
      Future<InetAddress[]> pending = lookup.submit(() -> InetAddress.getAllByName(HOST));
      dnsOk = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).length > 0;
    } catch (TimeoutException timeout) {
      dnsOk = false;
      error = "DNS_TIMEOUT";
    } catch (Exception failure) {
      dnsOk = false;
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      error = cause.getClass().getSimpleName();
    } finally {
      lookup.shutdownNow();
    }
    long dnsMs = SystemClock.elapsedRealtime() - start;
    if (!dnsOk) return new NetworkHealthPolicy.Probe(false, false, dnsMs, 0, error);

    long httpsStart = SystemClock.elapsedRealtime();
    boolean httpsOk = false;
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL("https://" + HOST + "/").openConnection();
      connection.setRequestMethod("HEAD");
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setConnectTimeout(TIMEOUT_MS);
      connection.setReadTimeout(TIMEOUT_MS);
      connection.setRequestProperty("Connection", "close");
      httpsOk = connection.getResponseCode() > 0;
    } catch (Exception failure) {
      error = failure.getClass().getSimpleName();
    } finally {
      if (connection != null) connection.disconnect();
    }
    return new NetworkHealthPolicy.Probe(
        true, httpsOk, dnsMs, SystemClock.elapsedRealtime() - httpsStart, error);
  }
}
