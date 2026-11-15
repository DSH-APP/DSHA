package com.deepseekharness.app.device;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import com.deepseekharness.app.util.NetworkSnapshot;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全 App 唯一的默认网络监听：所有关心网络的协作者都订阅这里，不再各自注册回调。
 *
 * <p>系统回调在独立 HandlerThread 上合并为 {@link NetworkSnapshot}；监听方在同一线程收到
 * AVAILABLE / LOST / CHANGED 三类事件，耗时工作须自行转到其它线程。旧网络迟到的 onLost
 * 不会覆盖已切换的新网络。进程内惰性启动一次，随进程存活。
 */
public final class NetworkStateMonitor {
  private static final String TAG = "DSHA-NET";

  public enum Event {
    AVAILABLE,
    LOST,
    CHANGED
  }

  public interface Listener {
    void onNetwork(Event event, NetworkSnapshot snapshot);
  }

  private static final Object START = new Object();
  private static volatile NetworkStateMonitor instance;

  private final ConnectivityManager manager;
  private final Handler handler;
  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
  private volatile NetworkSnapshot snapshot = NetworkSnapshot.NONE;
  private volatile long changedAt = 0;
  private volatile boolean registered = false;
  // 仅在 handler 线程读写
  private Network current;
  private NetworkCapabilities capabilities;
  private LinkProperties links;
  private boolean blocked;

  private NetworkStateMonitor(Context context) {
    manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    HandlerThread thread = new HandlerThread("dsha-network");
    thread.setDaemon(true);
    thread.start();
    handler = new Handler(thread.getLooper());
  }

  /** 惰性启动；重复调用只返回同一实例。注册失败时仍返回实例，快照由 {@link #query} 兜底。 */
  public static NetworkStateMonitor ensureStarted(Context context) {
    NetworkStateMonitor local = instance;
    if (local != null) return local;
    synchronized (START) {
      if (instance == null) {
        NetworkStateMonitor created = new NetworkStateMonitor(context.getApplicationContext());
        created.register();
        instance = created;
      }
      return instance;
    }
  }

  public void addListener(Listener listener) {
    if (listener != null) listeners.addIfAbsent(listener);
  }

  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  public NetworkSnapshot current() {
    return snapshot;
  }

  /** 诊断：回调是否在工作 + 距最近一次变化的时间（快照另行输出）。 */
  public String describe() {
    long at = changedAt;
    return "monitor="
        + (registered ? "registered" : "unavailable")
        + "\nchanged_ago_ms="
        + (at == 0 ? -1 : SystemClock.elapsedRealtime() - at)
        + "\n";
  }

  /**
   * 立即同步查询当前默认网络（跨 Binder，不在锁内调用）。用于启动瞬间监听尚未回调时；
   * 与回调使用同一套字段提取，避免两处解释不一致。
   */
  public static NetworkSnapshot query(Context context) {
    try {
      ConnectivityManager cm =
          (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (cm == null) return NetworkSnapshot.NONE;
      Network network = cm.getActiveNetwork();
      if (network == null) return NetworkSnapshot.NONE;
      return build(
          network, cm.getNetworkCapabilities(network), cm.getLinkProperties(network), false);
    } catch (RuntimeException unavailable) {
      return NetworkSnapshot.NONE;
    }
  }

  private void register() {
    if (manager == null) return;
    try {
      manager.registerDefaultNetworkCallback(callback, handler);
      registered = true;
    } catch (RuntimeException error) {
      // 回调数达到系统上限或权限异常：保留查询路径，不影响启动。
      Log.w(TAG, "default network callback unavailable: " + error.getClass().getSimpleName());
    }
  }

  private final ConnectivityManager.NetworkCallback callback =
      new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
          current = network;
          capabilities = null;
          links = null;
          blocked = false;
          publish(Event.AVAILABLE);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities value) {
          if (!network.equals(current)) return;
          capabilities = value;
          publish(Event.CHANGED);
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties value) {
          if (!network.equals(current)) return;
          links = value;
          publish(Event.CHANGED);
        }

        @Override
        public void onBlockedStatusChanged(Network network, boolean value) {
          if (!network.equals(current)) return;
          blocked = value;
          publish(Event.CHANGED);
        }

        @Override
        public void onLost(Network network) {
          // 切网时旧网络的 onLost 可能晚于新网络 onAvailable；只认当前默认网络。
          if (!network.equals(current)) return;
          current = null;
          capabilities = null;
          links = null;
          blocked = false;
          publish(Event.LOST);
        }
      };

  private void publish(Event event) {
    NetworkSnapshot next =
        current == null ? NetworkSnapshot.NONE : build(current, capabilities, links, blocked);
    boolean same = next.equals(snapshot);
    if (same && event == Event.CHANGED) return;
    snapshot = next;
    changedAt = SystemClock.elapsedRealtime();
    for (Listener listener : listeners) {
      try {
        listener.onNetwork(event, next);
      } catch (RuntimeException error) {
        Log.w(TAG, "network listener failed: " + error.getClass().getSimpleName());
      }
    }
  }

  private static NetworkSnapshot build(
      Network network, NetworkCapabilities caps, LinkProperties lp, boolean blocked) {
    boolean vpn = false, validated = false;
    String transport = "other";
    if (caps != null) {
      vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
      validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
      transport =
          NetworkSnapshot.transport(
              caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
              caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
              caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
              caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
    }
    List<String> dns = new ArrayList<>();
    String privateMode = "unknown", privateServer = "";
    boolean ipv4 = false, ipv6 = false;
    if (lp != null) {
      for (InetAddress address : lp.getDnsServers()) dns.add(address.getHostAddress());
      String name = lp.getPrivateDnsServerName();
      privateMode = NetworkSnapshot.privateDnsMode(lp.isPrivateDnsActive(), name);
      privateServer = name == null ? "" : name;
      for (LinkAddress link : lp.getLinkAddresses()) {
        InetAddress address = link.getAddress();
        if (address instanceof Inet4Address) ipv4 = true;
        else if (address instanceof Inet6Address
            && !address.isLinkLocalAddress()
            && !address.isLoopbackAddress()) ipv6 = true;
      }
    }
    return new NetworkSnapshot(
        network.getNetworkHandle(),
        transport,
        vpn,
        validated,
        blocked,
        dns,
        privateMode,
        privateServer,
        ipv4,
        ipv6);
  }
}
