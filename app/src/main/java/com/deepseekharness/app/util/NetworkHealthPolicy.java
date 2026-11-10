package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络健康一期：把检测信号归类为一个结论，并管理去重与环形记录。只判定，不做任何修复动作。
 *
 * <p>纯逻辑，零 Android import。结论优先级：前台服务缺失 > 系统拦截 > 容器 DNS 过期 > 出网失败 >
 * 疑似冻结。证据只保存枚举值、布尔值、耗时和异常类名，不保存 IP、DNS 地址、URL 路径或凭据。
 */
public final class NetworkHealthPolicy {
  /** 心跳间隔；Web 运行期间按此节拍记录调度时间。 */
  public static final long HEARTBEAT_MS = 60_000;

  /** 实际间隔超过这个值视为曾被冻结或深度休眠（约 3 个心跳）。 */
  public static final long FROZEN_GAP_MS = 180_000;

  /** 同一结论在此窗口内只新增一条事件，其余累加计数。 */
  public static final long DEDUP_MS = 10 * 60_000;

  /** 私有记录最多保留的事件数。 */
  public static final int MAX_EVENTS = 200;

  public enum Verdict {
    OK,
    FGS_MISSING,
    SYSTEM_BLOCKED,
    DNS_STALE,
    EGRESS_FAILED,
    SUSPECT_FROZEN
  }

  /** 一次检测收集到的信号。probe 为 null 表示本次未探测。 */
  public static final class Signals {
    public boolean webActive;
    public boolean foregroundService;
    public boolean connected;
    public boolean blocked;
    public boolean validated;
    public boolean vpn;
    public String transport = "none";
    public String resolvState = "unavailable";
    public Probe probe;
    public long frozenGapMs;
  }

  /** 宿主侧出网探测结果；error 只允许异常类名或固定代码。 */
  public static final class Probe {
    public final boolean dnsOk;
    public final boolean httpsOk;
    public final long dnsMs;
    public final long httpsMs;
    public final String error;

    public Probe(boolean dnsOk, boolean httpsOk, long dnsMs, long httpsMs, String error) {
      this.dnsOk = dnsOk;
      this.httpsOk = httpsOk;
      this.dnsMs = dnsMs;
      this.httpsMs = httpsMs;
      this.error = errorName(error);
    }

    public boolean ok() {
      return dnsOk && httpsOk;
    }
  }

  public static final class Event {
    public final long at;
    public final Verdict verdict;
    public final int count;
    public final String evidence;

    public Event(long at, Verdict verdict, int count, String evidence) {
      this.at = at;
      this.verdict = verdict;
      this.count = Math.max(1, count);
      this.evidence = evidence == null ? "" : evidence;
    }
  }

  private NetworkHealthPolicy() {}

  /** 归类：Web 未运行时只看系统拦截和 DNS，不报前台服务缺失与冻结。 */
  public static Verdict classify(Signals s) {
    if (s == null) return Verdict.OK;
    if (s.webActive && !s.foregroundService) return Verdict.FGS_MISSING;
    if (s.blocked) return Verdict.SYSTEM_BLOCKED;
    if (s.connected && "stale".equals(s.resolvState)) return Verdict.DNS_STALE;
    if (s.connected && s.probe != null && !s.probe.ok()) return Verdict.EGRESS_FAILED;
    if (s.webActive && s.frozenGapMs >= FROZEN_GAP_MS) return Verdict.SUSPECT_FROZEN;
    return Verdict.OK;
  }

  /** 信号可疑时值得补一次出网探测；已经能解释原因的结论不再探测。 */
  public static boolean shouldProbe(Signals s) {
    if (s == null || !s.connected || s.blocked) return false;
    if (s.webActive && !s.foregroundService) return false;
    return !s.validated || "stale".equals(s.resolvState);
  }

  /** 心跳实际间隔超出预期的部分；未超过阈值返回 0。 */
  public static long frozenGap(long previousBeat, long now) {
    if (previousBeat <= 0 || now < previousBeat) return 0;
    long gap = now - previousBeat;
    return gap >= FROZEN_GAP_MS ? gap : 0;
  }

  /** 证据串：固定键顺序，便于用户直接贴到 issue。 */
  public static String evidence(Signals s) {
    StringBuilder out = new StringBuilder();
    out.append("web=").append(s.webActive ? 1 : 0);
    out.append(" fgs=").append(s.foregroundService ? 1 : 0);
    out.append(" net=").append(s.connected ? known(s.transport, TRANSPORTS) : "none");
    out.append(" vpn=").append(s.vpn ? 1 : 0);
    out.append(" validated=").append(s.validated ? 1 : 0);
    out.append(" blocked=").append(s.blocked ? 1 : 0);
    out.append(" resolv=").append(known(s.resolvState, RESOLV_STATES));
    if (s.probe != null) {
      out.append(" dns=").append(s.probe.dnsOk ? s.probe.dnsMs + "ms" : "fail");
      out.append(" https=").append(s.probe.httpsOk ? s.probe.httpsMs + "ms" : "fail");
      if (!s.probe.error.isEmpty()) out.append(" err=").append(s.probe.error);
    }
    if (s.frozenGapMs > 0) out.append(" gap=").append(s.frozenGapMs / 1000).append('s');
    return out.toString();
  }

  /**
   * 追加一条结论。OK 不入记录；与最近同结论事件间隔在去重窗口内时只累加计数。返回新列表，超出上限丢弃最旧。
   */
  public static List<Event> append(List<Event> events, long now, Verdict verdict, String evidence) {
    List<Event> next = new ArrayList<>(events == null ? List.of() : events);
    if (verdict == null || verdict == Verdict.OK) return next;
    for (int i = next.size() - 1; i >= 0; i--) {
      Event last = next.get(i);
      if (last.verdict != verdict) continue;
      if (now >= last.at && now - last.at < DEDUP_MS) {
        next.set(i, new Event(last.at, verdict, last.count + 1, evidence));
        return next;
      }
      break;
    }
    next.add(new Event(now, verdict, 1, evidence));
    while (next.size() > MAX_EVENTS) next.remove(0);
    return next;
  }

  /** 最近 n 条，新的在前。 */
  public static List<Event> recent(List<Event> events, int n) {
    List<Event> copy = new ArrayList<>(events == null ? List.of() : events);
    Collections.reverse(copy);
    return copy.subList(0, Math.min(Math.max(0, n), copy.size()));
  }

  /** 一行一条：at|VERDICT|count|evidence。证据内的分隔符与换行已在 evidence 中排除。 */
  public static String serialize(List<Event> events) {
    StringBuilder out = new StringBuilder("DSHA_NET_HEALTH_V1\n");
    if (events != null)
      for (Event e : events)
        out.append(e.at)
            .append('|')
            .append(e.verdict.name())
            .append('|')
            .append(e.count)
            .append('|')
            .append(e.evidence.replace('|', ' ').replace('\n', ' ').replace('\r', ' '))
            .append('\n');
    return out.toString();
  }

  /** 解析记录；未知结论或坏行跳过，不让一行坏数据清空全部记录。 */
  public static List<Event> parse(String text) {
    List<Event> events = new ArrayList<>();
    if (text == null || !text.startsWith("DSHA_NET_HEALTH_V1")) return events;
    for (String line : text.split("\\R")) {
      String[] parts = line.split("\\|", 4);
      if (parts.length != 4) continue;
      try {
        events.add(
            new Event(
                Long.parseLong(parts[0]),
                Verdict.valueOf(parts[1]),
                Integer.parseInt(parts[2]),
                parts[3]));
      } catch (IllegalArgumentException ignored) {
        // 跳过坏行
      }
    }
    while (events.size() > MAX_EVENTS) events.remove(0);
    return events;
  }

  /** 从 DSHA_NET_V1 文本取 resolv_state：任一 rootfs 过期即 stale，全部 current 才 current。 */
  public static String resolvState(String netReport) {
    if (netReport == null) return "unavailable";
    String result = "unavailable";
    for (String line : netReport.split("\\R")) {
      if (!line.startsWith("resolv_state=")) continue;
      String value = line.substring("resolv_state=".length()).trim();
      if ("stale".equals(value)) return "stale";
      if ("unavailable".equals(result) || "current".equals(result)) result = value;
    }
    return safeToken(result);
  }

  /** 体检报告去掉 DNS 地址行，私有 DNS 只保留模式不保留服务器名。 */
  public static String redactNetReport(String netReport) {
    if (netReport == null) return "";
    StringBuilder out = new StringBuilder();
    for (String line : netReport.split("\\R")) {
      if (line.isEmpty() || line.startsWith("device_dns=") || line.startsWith("resolv_dns="))
        continue;
      if (line.startsWith("private_dns=")) {
        int space = line.indexOf(' ');
        if (space > 0) line = line.substring(0, space);
      }
      out.append(line).append('\n');
    }
    return out.toString();
  }

  /** 每个结论的计数，供面板摘要。 */
  public static Map<Verdict, Integer> totals(List<Event> events) {
    Map<Verdict, Integer> totals = new LinkedHashMap<>();
    if (events != null) for (Event e : events) totals.merge(e.verdict, e.count, Integer::sum);
    return totals;
  }

  private static final List<String> TRANSPORTS =
      List.of("wifi", "cellular", "ethernet", "bluetooth", "other", "none");
  private static final List<String> RESOLV_STATES =
      List.of("current", "stale", "user", "empty", "unavailable");

  /** 白名单取值：不在已知集合内一律记为 other，不把原始字符串写进记录。 */
  static String known(String value, List<String> allowed) {
    return value != null && allowed.contains(value) ? value : "other";
  }

  /** 只取异常简单类名或固定代码：丢弃冒号后的消息与包名，避免带出主机或地址。 */
  static String errorName(String value) {
    if (value == null) return "";
    String head = value.split("[:\\s]", 2)[0];
    int dot = head.lastIndexOf('.');
    return safeToken(dot >= 0 ? head.substring(dot + 1) : head);
  }

  /** 只保留 [A-Za-z0-9._-]，其余替换为 _，最长 64。 */
  static String safeToken(String value) {
    if (value == null) return "";
    String clean = value.replaceAll("[^A-Za-z0-9._-]", "_");
    return clean.length() > 64 ? clean.substring(0, 64) : clean;
  }
}
