package com.deepseekharness.app.device;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.NetworkSnapshot;
import com.deepseekharness.app.util.ResolverConfig;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * /app/net：一条命令看清「宿主网络 → 容器 resolv.conf」整条链。
 *
 * <p>只读：不写文件、不发 DNS 查询。不输出本机 IP、SSID 或运营商名。用户反馈网络问题时，
 * 让对方贴这段输出即可定位是宿主无网、托管段过期、用户自定义 DNS 还是系统私有 DNS。
 */
public final class NetworkDiagnostics {
  private static final long MAX_RESOLV_BYTES = 64 * 1024;

  private NetworkDiagnostics() {}

  public static String render(Context context, String dnsMode, List<String> extraRoots) {
    NetworkStateMonitor monitor = NetworkStateMonitor.ensureStarted(context);
    NetworkSnapshot snapshot = monitor.current();
    // 回调尚未到达（刚启动）时以即时查询为准，避免误报 none。
    if (!snapshot.connected()) snapshot = NetworkStateMonitor.query(context);
    StringBuilder out = new StringBuilder("DSHA_NET_V1\n");
    out.append(monitor.describe());
    out.append(snapshot.render());
    out.append("dns_mode=").append(dnsMode).append('\n');
    out.append(com.deepseekharness.app.runtime.ResolverRefresher.describe());
    Set<String> roots =
        new LinkedHashSet<>(com.deepseekharness.app.runtime.ResolverRefresher.roots());
    if (extraRoots != null) roots.addAll(extraRoots);
    int index = 0;
    for (String root : roots) {
      File target = new File(root, "etc/resolv.conf");
      String text = read(target);
      if (text == null) continue;
      out.append("rootfs.")
          .append(index++)
          .append('=')
          .append(new File(root).getName())
          .append('\n');
      out.append(ResolverConfig.describe(text, snapshot.dnsServers));
    }
    if (index == 0) out.append("resolv_state=unavailable\n");
    return out.toString();
  }

  /** 特殊文件与软链接不跟随读取；读不到返回 null。 */
  private static String read(File target) {
    try {
      if (Compat.isSymbolicLink(target) || !target.isFile()) return null;
      if (target.length() > MAX_RESOLV_BYTES) return null;
      return Compat.readAll(target);
    } catch (Exception error) {
      return null;
    }
  }
}
