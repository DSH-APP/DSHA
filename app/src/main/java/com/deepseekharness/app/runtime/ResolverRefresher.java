package com.deepseekharness.app.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import com.deepseekharness.app.device.NetworkStateMonitor;
import com.deepseekharness.app.util.NetworkSnapshot;
import com.deepseekharness.app.util.ResolverRefreshPolicy;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 切网后把设备 DNS 重新投影到已准备过的容器 resolv.conf。
 *
 * <p>只刷新本进程已经由 RuntimeTools 准备过的 rootfs；规则与启动时完全相同（只动 DSHA 托管段，
 * 用户 nameserver、软链接和特殊文件保持原样）。容器内 glibc 2.26+ 发现文件变化会自动重读，
 * 运行中的 Node / curl / git / pip 不需要重启。维护屏障独占期间推迟，不与数据迁移竞争。
 */
public final class ResolverRefresher {
  private static final String TAG = "DSHA-DNS";
  private static final Object START = new Object();
  private static volatile ResolverRefresher instance;

  private final Context context;
  private final Handler handler;
  private final Set<String> roots = new CopyOnWriteArraySet<>();
  private final Runnable refresh = this::run;
  // 刷新线程与启动线程都会更新，均为整体替换的不可变值
  private volatile List<String> applied = List.of();
  private volatile long refreshedAt = 0;
  private volatile String lastResult = "none";

  private ResolverRefresher(Context context) {
    this.context = context;
    HandlerThread thread = new HandlerThread("dsha-dns-refresh");
    thread.setDaemon(true);
    thread.start();
    handler = new Handler(thread.getLooper());
  }

  /** 在任何锁之外调用：首次会注册系统网络回调（跨 Binder）。 */
  static void start(Context context) {
    if (context == null) return;
    try {
      get(context);
    } catch (RuntimeException error) {
      Log.w(TAG, "resolver refresher unavailable: " + error.getClass().getSimpleName());
    }
  }

  /** 由 RuntimeTools 在每次协调 resolv.conf 时调用：只登记 rootfs，不做系统调用。 */
  static void register(Context context, File rootfs, List<String> appliedDns) {
    if (context == null || rootfs == null) return;
    ResolverRefresher refresher = instance;
    if (refresher == null) return; // start 失败时保持原有「仅启动时协调」的行为
    String path;
    try {
      path = rootfs.getCanonicalPath();
    } catch (IOException error) {
      path = rootfs.getAbsolutePath();
    }
    refresher.roots.add(path);
    if (appliedDns != null && !appliedDns.isEmpty()) refresher.applied = List.copyOf(appliedDns);
  }

  private static ResolverRefresher get(Context context) {
    ResolverRefresher local = instance;
    if (local != null) return local;
    synchronized (START) {
      if (instance == null) {
        Context app = context.getApplicationContext();
        ResolverRefresher created = new ResolverRefresher(app == null ? context : app);
        NetworkStateMonitor.ensureStarted(created.context)
            .addListener((event, snapshot) -> created.onNetwork(snapshot));
        instance = created;
      }
      return instance;
    }
  }

  /** 诊断：最近一次刷新结果；没有实例时说明本进程尚未准备过容器。 */
  public static String describe() {
    ResolverRefresher local = instance;
    if (local == null) return "resolver_refresh=idle\n";
    long at = local.refreshedAt;
    return "resolver_refresh="
        + local.lastResult
        + "\nresolver_refresh_ago_ms="
        + (at == 0 ? -1 : SystemClock.elapsedRealtime() - at)
        + "\nresolver_roots="
        + local.roots.size()
        + "\n";
  }

  /** 已登记 rootfs 的规范路径（只读副本），供诊断读取 resolv.conf。 */
  public static List<String> roots() {
    ResolverRefresher local = instance;
    return local == null ? List.of() : List.copyOf(local.roots);
  }

  private void onNetwork(NetworkSnapshot snapshot) {
    if (!ResolverRefreshPolicy.shouldRefresh(applied, snapshot.dnsServers)) return;
    // 同一次切网会连续来几条回调；合并成一次写入。
    handler.removeCallbacks(refresh);
    handler.postDelayed(refresh, ResolverRefreshPolicy.DEBOUNCE_MS);
  }

  private void run() {
    NetworkSnapshot snapshot = NetworkStateMonitor.ensureStarted(context).current();
    List<String> dns = snapshot.dnsServers;
    if (!ResolverRefreshPolicy.shouldRefresh(applied, dns)) return;
    if (com.deepseekharness.app.util.MaintenanceGate.shared().isExclusive()) {
      // 数据维护可能正在移动或替换 rootfs；结束后再写，不在中途改动。
      lastResult = "deferred_maintenance";
      handler.removeCallbacks(refresh);
      handler.postDelayed(refresh, ResolverRefreshPolicy.MAINTENANCE_RETRY_MS);
      return;
    }
    int written = 0, failed = 0;
    for (String root : roots) {
      File rootfs = new File(root);
      if (!new File(rootfs, "etc").isDirectory()) continue;
      try {
        RuntimeTools.refreshResolver(context, rootfs, dns);
        written++;
      } catch (IOException | RuntimeException error) {
        failed++;
        Log.w(TAG, "resolv.conf refresh failed: " + error.getClass().getSimpleName());
      }
    }
    refreshedAt = SystemClock.elapsedRealtime();
    lastResult = failed > 0 ? "failed" : written > 0 ? "ok" : "no_roots";
    // 失败不记为已投影：下一次网络事件或下一次进程启动会再协调，不在这里循环重试。
    if (failed == 0) applied = List.copyOf(dns);
  }
}
