package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 宿主默认网络的不可变快照：全 App 唯一的网络事实来源。
 *
 * <p>只保存诊断与 DNS 投影需要的字段，不含 IP 地址、SSID 或运营商名等可识别信息。
 * {@link #equals} 只比较网络事实，供监听方去重（信号强度类能力变化不应触发刷新）。
 */
public final class NetworkSnapshot {
  public static final NetworkSnapshot NONE =
      new NetworkSnapshot(0, "none", false, false, false, List.of(), "unknown", "", false, false);

  /** Android Network#getNetworkHandle；0 表示没有默认网络。 */
  public final long handle;

  /** wifi / cellular / ethernet / bluetooth / other / none。VPN 单列在 {@link #vpn}。 */
  public final String transport;

  public final boolean vpn;
  public final boolean validated;
  public final boolean blocked;

  /** 已去重、去掉带 scope 的链路本地地址后的设备 DNS（数字地址，保持系统顺序）。 */
  public final List<String> dnsServers;

  /** off / opportunistic / strict / unknown。 */
  public final String privateDns;

  public final String privateDnsServer;
  public final boolean ipv4;
  public final boolean ipv6;

  public NetworkSnapshot(
      long handle,
      String transport,
      boolean vpn,
      boolean validated,
      boolean blocked,
      List<String> dnsServers,
      String privateDns,
      String privateDnsServer,
      boolean ipv4,
      boolean ipv6) {
    this.handle = handle;
    this.transport = transport == null || transport.isEmpty() ? "other" : transport;
    this.vpn = vpn;
    this.validated = validated;
    this.blocked = blocked;
    this.dnsServers = Collections.unmodifiableList(normalizeDns(dnsServers));
    this.privateDns = privateDns == null || privateDns.isEmpty() ? "unknown" : privateDns;
    this.privateDnsServer = privateDnsServer == null ? "" : privateDnsServer;
    this.ipv4 = ipv4;
    this.ipv6 = ipv6;
  }

  public boolean connected() {
    return handle != 0 && !"none".equals(transport);
  }

  /** 由 NetworkCapabilities 的传输标志选主传输；VPN 只在没有底层传输时记为 other。 */
  public static String transport(
      boolean wifi, boolean cellular, boolean ethernet, boolean bluetooth) {
    if (wifi) return "wifi";
    if (cellular) return "cellular";
    if (ethernet) return "ethernet";
    if (bluetooth) return "bluetooth";
    return "other";
  }

  /** IPv6 链路本地 DNS 常带 %iface；resolv.conf 校验不接受，提前剔除并去重。 */
  public static List<String> normalizeDns(List<String> servers) {
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    if (servers != null)
      for (String server : servers) {
        if (server == null) continue;
        String value = server.trim();
        if (value.isEmpty() || value.indexOf('%') >= 0) continue;
        unique.add(value);
      }
    return new ArrayList<>(unique);
  }

  /** 诊断行；固定英文键，便于脚本与用户截图对照。 */
  public String render() {
    StringBuilder out = new StringBuilder();
    out.append("network=").append(connected() ? transport : "none").append('\n');
    out.append("vpn=").append(vpn).append('\n');
    out.append("validated=").append(validated).append('\n');
    out.append("blocked=").append(blocked).append('\n');
    out.append("ip=")
        .append(ipv4 && ipv6 ? "dual" : ipv4 ? "ipv4" : ipv6 ? "ipv6" : "none")
        .append('\n');
    out.append("device_dns=")
        .append(dnsServers.isEmpty() ? "-" : String.join(",", dnsServers))
        .append('\n');
    out.append("private_dns=").append(privateDns);
    if (!privateDnsServer.isEmpty()) out.append(' ').append(privateDnsServer);
    out.append('\n');
    return out.toString();
  }

  /** 私有 DNS 模式归一化；输入来自 LinkProperties 的两个布尔值。 */
  public static String privateDnsMode(boolean active, String serverName) {
    if (!active) return "off";
    return serverName == null || serverName.isEmpty() ? "opportunistic" : "strict";
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof NetworkSnapshot)) return false;
    NetworkSnapshot that = (NetworkSnapshot) other;
    return handle == that.handle
        && vpn == that.vpn
        && validated == that.validated
        && blocked == that.blocked
        && ipv4 == that.ipv4
        && ipv6 == that.ipv6
        && transport.equals(that.transport)
        && dnsServers.equals(that.dnsServers)
        && privateDns.equals(that.privateDns)
        && privateDnsServer.equals(that.privateDnsServer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        handle,
        transport,
        vpn,
        validated,
        blocked,
        dnsServers,
        privateDns,
        privateDnsServer,
        ipv4,
        ipv6);
  }

  @Override
  public String toString() {
    return String.format(
        Locale.ROOT,
        "NetworkSnapshot{%s vpn=%s validated=%s dns=%d}",
        connected() ? transport : "none",
        vpn,
        validated,
        dnsServers.size());
  }
}
