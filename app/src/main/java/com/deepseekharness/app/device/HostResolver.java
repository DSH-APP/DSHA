package com.deepseekharness.app.device;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.Network;
import android.os.CancellationSignal;
import com.deepseekharness.app.util.HostLookup;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * /app/net/resolve：用 Android 系统解析器在当前默认网络上解析域名。
 *
 * <p>这是容器 resolv.conf 失效时的兜底：系统解析器会走私有 DNS（DoT）、DNS64 与 VPN 分流，
 * 这些都无法写进 resolv.conf。只返回数字地址，不建立连接、不重放请求、不记录主机名；
 * 并发与时限都有上限，避免占满 4 线程的桥。
 */
public final class HostResolver {
  private static final long TIMEOUT_MS = 4_000;
  private static final Semaphore SLOTS = new Semaphore(2);
  private static final Executor DIRECT = Runnable::run;
  private static final int RCODE_NXDOMAIN = 3;

  private HostResolver() {}

  public static String resolve(Context context, String rawHost, String rawFamily) {
    String host = HostLookup.normalize(rawHost);
    int family = HostLookup.family(rawFamily);
    if (host == null || family < 0) return HostLookup.render(HostLookup.Status.INVALID, null);
    if (!SLOTS.tryAcquire()) return HostLookup.render(HostLookup.Status.BUSY, null);
    try {
      ConnectivityManager cm =
          (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      Network network = cm == null ? null : cm.getActiveNetwork();
      if (network == null) return HostLookup.render(HostLookup.Status.NO_NETWORK, null);
      return query(network, host, family);
    } catch (RuntimeException error) {
      return HostLookup.render(HostLookup.Status.FAILED, null);
    } finally {
      SLOTS.release();
    }
  }

  private static String query(Network network, String host, int family) {
    List<Integer> types = new ArrayList<>();
    if (family != 6) types.add(DnsResolver.TYPE_A);
    if (family != 4) types.add(DnsResolver.TYPE_AAAA);
    CountDownLatch done = new CountDownLatch(types.size());
    CancellationSignal cancel = new CancellationSignal();
    List<String> addresses = Collections.synchronizedList(new ArrayList<>());
    // 任一类型的明确结果即可；全部失败时取最有信息量的那个状态。
    final HostLookup.Status[] worst = {HostLookup.Status.NODATA};
    for (int type : types) {
      DnsResolver.getInstance()
          .query(
              network,
              host,
              type,
              DnsResolver.FLAG_EMPTY,
              DIRECT,
              cancel,
              new DnsResolver.Callback<List<InetAddress>>() {
                @Override
                public void onAnswer(List<InetAddress> answer, int rcode) {
                  if (answer != null)
                    for (InetAddress address : answer) addresses.add(address.getHostAddress());
                  if (rcode == RCODE_NXDOMAIN) {
                    synchronized (worst) {
                      worst[0] = HostLookup.Status.NXDOMAIN;
                    }
                  }
                  done.countDown();
                }

                @Override
                public void onError(DnsResolver.DnsException error) {
                  synchronized (worst) {
                    if (worst[0] == HostLookup.Status.NODATA) worst[0] = HostLookup.Status.FAILED;
                  }
                  done.countDown();
                }
              });
    }
    boolean finished;
    try {
      finished = done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      finished = false;
    }
    if (!finished) cancel.cancel();
    List<String> selected;
    synchronized (addresses) {
      selected = HostLookup.select(new ArrayList<>(addresses), family);
    }
    if (!selected.isEmpty()) return HostLookup.render(HostLookup.Status.OK, selected);
    if (!finished) return HostLookup.render(HostLookup.Status.TIMEOUT, null);
    synchronized (worst) {
      return HostLookup.render(worst[0], null);
    }
  }
}
